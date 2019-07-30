package controllers

import akka.actor.typed.javadsl
import akka.actor.typed.javadsl.Receive
import akka.actor.typed.javadsl.{ ActorContext => JCtx }
import akka.actor.typed.scaladsl
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors.{ receive, receiveMessage, setup }
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ Behaviors, ActorContext => Ctx }
import akka.actor.typed.{ ActorRef, Behavior, Scheduler }
import akka.actor.{ Actor, ActorSystem, Props, typed }
import akka.japi.function.{ Function => JFunction }
import akka.util.Timeout
import com.google.inject.assistedinject.{ Assisted, FactoryModuleBuilder }
import com.google.inject.binder.LinkedBindingBuilder
import com.google.inject.name.Names
import com.google.inject.Injector
import com.google.inject.util.{ Providers, Types }
import com.google.inject.{ AbstractModule, Binder, Key, Provides, TypeLiteral }
import java.lang.reflect.{ Method, ParameterizedType, Type }
import javax.inject._
import play.api.inject.{ SimpleModule, bind }
import play.api.libs.concurrent.{ Akka, AkkaGuiceSupport, InjectedActorSupport }
import play.api.mvc._
import play.api.{ Configuration => Conf }
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.reflect.{ ClassTag, classTag }
import com.google.inject.Module

object Utils {
  def cast[A](value: Any): A                    = value.asInstanceOf[A]
  def classOfA[A: ClassTag](): Class[A]         = classTag[A].runtimeClass.asInstanceOf[Class[A]]
  def classOfSubA[A: ClassTag](): Class[_ <: A] = classOfA[A]

  def lookupConf(conf: Conf, key: String) = conf.getOptional[String](key).getOrElse("none")
  def rcv[A](onMessage: A => Unit): Behavior[A] = receiveMessage[A] { msg => onMessage(msg); Behaviors.same }
}; import Utils._

final case class Event(name: String)
final case class Greet(name: String, replyTo: ActorRef[String])
final case class GetConf(replyTo: ActorRef[String])
object   FooActor { def apply()           = rcv[Event]   { msg                       => println(s"foo => ${msg.name}")         } }
object HelloActor { def apply()           = rcv[Greet]   { case Greet(name, replyTo) => replyTo ! s"Hello, $name"              } }
object ConfdActor extends AbstractModule { @Provides() def apply(conf: Conf) = rcv[GetConf] { case GetConf(replyTo)     => replyTo ! lookupConf(conf, "my.cfg")   } }

final class   ScalaFooActor                        extends scaladsl.AbstractBehavior[Event]   { def onMessage(msg: Event)    = { println(s"foo => ${msg.name}")           ; this } }
final class ScalaHelloActor                        extends scaladsl.AbstractBehavior[Greet]   { def onMessage(msg: Greet)    = { msg.replyTo ! s"Hello, ${msg.name}"      ; this } }
final class ScalaConfdActor @Inject() (conf: Conf) extends scaladsl.AbstractBehavior[GetConf] { def onMessage(msg: GetConf)  = { msg.replyTo ! lookupConf(conf, "my.cfg") ; this } }

final class    JavaFooActor             extends  javadsl.AbstractBehavior[Event]   { def createReceive = newReceiveBuilder.onAnyMessage { msg => println(s"foo => ${msg.name}")                               ; this }.build() }
final class  JavaHelloActor             extends  javadsl.AbstractBehavior[Greet]   { def createReceive = newReceiveBuilder.onAnyMessage { case Greet(name, replyTo)   => replyTo ! s"Hello, $name"            ; this }.build() }
final class  JavaConfdActor(conf: Conf) extends  javadsl.AbstractBehavior[GetConf] { def createReceive = newReceiveBuilder.onAnyMessage { case GetConf(replyTo)       => replyTo ! lookupConf(conf, "my.cfg") ; this }.build() }

final class TypedActorRefProvider[T](protected val behavior: Behavior[T], protected val name: String) extends Provider[ActorRef[T]] {
  @Inject protected var system: ActorSystem = _
               lazy val get: ActorRef[T]    = system.spawn(behavior, name)
}
final class TypedActorRefProvider2[T: ClassTag](val name: String) extends Provider[ActorRef[T]] with ActorRefTypes {
  @Inject protected var system: ActorSystem   = _
  @Inject protected var injector: Injector    = _
            private def behavior: Behavior[T] = injector.getInstance(Key.get(behaviorOf[T]))
               lazy val get: ActorRef[T]      = system.spawn(behavior, name)
}
trait ActorRefTypes {
  /** Equivalent to `new TypeLiteral[ActorRef[A]]() {}`. */
  final def actorRefOf[A: ClassTag]            = tpeLit[ActorRef[A]](classOf[ActorRef[_]], classOfA[A])
  final def behaviorOf[A: ClassTag]            = tpeLit[Behavior[A]](classOf[Behavior[_]], classOfA[A])
  final def tpeLit[A](tpe: Type, targs: Type*) = cast[TypeLiteral[A]](tpeLitGet(typeCons(tpe, targs: _*)))
  final def tpeLitGet(tpe: Type)               = TypeLiteral.get(tpe)
  final def typeCons(tpe: Type, targs: Type*)  = Types.newParameterizedType(tpe, targs: _*)
}; object ActorRefTypes extends ActorRefTypes
trait AkkaTypedGuiceSupport extends AkkaGuiceSupport with ActorRefTypes { self: AbstractModule =>
  def bindTypedActor[T: ClassTag](behavior: Behavior[T], name: String) = {
    accessBinder
        .bind(actorRefOf[T])
        .toProvider(new TypedActorRefProvider[T](behavior, name))
        .asEagerSingleton()
  }
  def bindTypedActorRefOf[T: ClassTag](module: Module, name: String) = {
    accessBinder.install(module)
    accessBinder
        .bind(actorRefOf[T])
        .toProvider(new TypedActorRefProvider2[T](name))
        .asEagerSingleton()
  }
  def bindTypedActor3[T: ClassTag, B <: Behavior[T] : ClassTag](name: String) = {
    accessBinder
        .bind(behaviorOf[T])
        .to(classOfA[B])
        .asEagerSingleton()
    accessBinder
        .bind(actorRefOf[T])
        .toProvider(new TypedActorRefProvider2[T](name))
        .asEagerSingleton()
  }

  private def accessBinder: Binder = {
    val method: Method = classOf[AbstractModule].getDeclaredMethod("binder")
    if (!method.isAccessible)
      method.setAccessible(true)
    method.invoke(this).asInstanceOf[Binder]
  }
}

final class AppModule extends AbstractModule with AkkaTypedGuiceSupport {
  override def configure(): Unit = {
    bindTypedActor(FooActor(), "foo-actor2")
    bindTypedActor(HelloActor(), "hello-actor2")
//    bindTypedActorRefOf[GetConf](ConfdActor, "confd-actor2")
    bindTypedActor3[GetConf, ScalaConfdActor]("confd-actor4")
  }
}

abstract class SharedController(cc: ControllerComponents) extends AbstractController(cc) {
  protected def system: ActorSystem // for the ask pattern
  protected def fooActor: ActorRef[Event]
  protected def helloActor: ActorRef[Greet]
  protected def confdActor: ActorRef[GetConf]

  implicit val timeout: Timeout             = 3.seconds                       // for ask
  implicit val scheduler: Scheduler         = system.toTyped.scheduler        // for ask
  implicit val ec: ExecutionContextExecutor = system.toTyped.executionContext // for Future map

  final def fireEvent  = Action {  fooActor.tell(Event("a message"));   Ok("fired event")  }
  final def greetings  = Action.async(for (rsp <- helloActor.ask[String](Greet("Dale", _))) yield Ok(rsp))
  final def lookupConf = Action.async(for (rsp <- confdActor.ask[String](GetConf(_))) yield Ok(rsp))
}

@Singleton final class AController @Inject()(conf: Conf, cc: ControllerComponents, protected val system: ActorSystem) extends SharedController(cc) {
  val fooActor    = system.spawn(FooActor(),                            "foo-actor1")
  val helloActor  = system.spawn(HelloActor(),                          "hello-actor1")
  val confdActor  = system.spawn(ConfdActor(conf),                      "confd-actor1")
  val confdActor3 = system.spawn(new ScalaConfdActor(conf),             "confd-actor3")
}

@Singleton final class BController @Inject()(conf: Conf, cc: ControllerComponents, protected val system: ActorSystem,
    protected val fooActor: ActorRef[Event],
    protected val helloActor: ActorRef[Greet],
    protected val confdActor: ActorRef[GetConf],
) extends SharedController(cc)
