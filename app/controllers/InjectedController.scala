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
import com.google.inject.util.{ Providers, Types }
import com.google.inject.{ AbstractModule, Binder, Key, Provides, TypeLiteral }
import java.lang.reflect.{ Method, ParameterizedType, Type }
import javax.inject._
import play.api.inject.{ Injector, SimpleModule, bind }
import play.api.libs.concurrent.{ Akka, AkkaGuiceSupport, InjectedActorSupport }
import play.api.mvc._
import play.api.{ Configuration => Conf }
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.reflect.{ ClassTag, classTag }

// TODO: all class subtypes of Behavior, for the 2 AbstractBehaviors and ES behaviors
// TODO: bindTypedActor spawn name, but not annotated name
// TODO: assisted injection
// TODO: maybe descope DI for functional behavior
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

final class   ScalaFooActor             extends scaladsl.AbstractBehavior[Event]   { def onMessage(msg: Event)    = { println(s"foo => ${msg.name}")           ; this } }
final class ScalaHelloActor             extends scaladsl.AbstractBehavior[Greet]   { def onMessage(msg: Greet)    = { msg.replyTo ! s"Hello, ${msg.name}"      ; this } }
final class ScalaConfdActor(conf: Conf) extends scaladsl.AbstractBehavior[GetConf] { def onMessage(msg: GetConf)  = { msg.replyTo ! lookupConf(conf, "my.cfg") ; this } }

final class    JavaFooActor             extends  javadsl.AbstractBehavior[Event]   { def createReceive = newReceiveBuilder.onAnyMessage { msg => println(s"foo => ${msg.name}")                               ; this }.build() }
final class  JavaHelloActor             extends  javadsl.AbstractBehavior[Greet]   { def createReceive = newReceiveBuilder.onAnyMessage { case Greet(name, replyTo)   => replyTo ! s"Hello, $name"            ; this }.build() }
final class  JavaConfdActor(conf: Conf) extends  javadsl.AbstractBehavior[GetConf] { def createReceive = newReceiveBuilder.onAnyMessage { case GetConf(replyTo)       => replyTo ! lookupConf(conf, "my.cfg") ; this }.build() }

abstract class AbstractTypedActorRefProvider[T] extends Provider[ActorRef[T]] {
  protected val behavior: Behavior[T]
  protected val name: String

  @Inject protected var system: ActorSystem = _

  final lazy val get: ActorRef[T] = system.spawn(behavior, name)
}

final class TypedActorRefProvider2[T: ClassTag](val name: String) extends Provider[ActorRef[T]] with ActorRefTypes {
  @Inject protected var system: ActorSystem                  = _
  @Inject protected var injector: com.google.inject.Injector = _

  lazy val get: ActorRef[T] = system.spawn(injector.getInstance(Key.get(behaviorOf[T])), name)
}

abstract class TypedActorRefProvider3[T](val name: String) extends Provider[ActorRef[T]] {
  @Inject final protected var system: ActorSystem   = _
  protected var behavior: Behavior[T]

  final lazy val get: ActorRef[T] = system.spawn(behavior, name)
}

class TypedActorRefProvider[T](
    final protected val behavior: Behavior[T],
    final protected val name: String,
) extends AbstractTypedActorRefProvider[T]

final class AnotherTypedActorRefProvider[T: ClassTag](name: String) extends Provider[ActorRef[T]] {
  @Inject private var system: ActorSystem = _
  @Inject private var injector: Injector  = _

  lazy val get = {
    val behavior = injector.instanceOf[Behavior[T]]
    system.spawn(behavior, name)
  }
}

object TypedAkka {
  def typedProviderOf[T](behavior: Behavior[T], name: String) = new TypedActorRefProvider[T](behavior, name)
}

trait ActorRefTypes {
  /** Equivalent to `new TypeLiteral[ActorRef[A]]() {}`. */
  final def actorRefOf[A: ClassTag]            = tpeLit[ActorRef[A]](classOf[ActorRef[_]], classOfA[A])
  final def behaviorOf[A: ClassTag]            = tpeLit[Behavior[A]](classOf[Behavior[_]], classOfA[A])
  final def tpeLit[A](tpe: Type, targs: Type*) = cast[TypeLiteral[A]](tpeLitGet(typeCons(tpe, targs: _*)))
  final def tpeLitGet(tpe: Type)               = TypeLiteral.get(tpe)
  final def typeCons(tpe: Type, targs: Type*)  = Types.newParameterizedType(tpe, targs: _*)
}

object ActorRefTypes extends ActorRefTypes

trait AkkaTypedGuiceSupport extends AkkaGuiceSupport with ActorRefTypes { self: AbstractModule =>
  def bindTypedActor[T: ClassTag](behavior: Behavior[T], name: String) =
    accessBinder
        .bind(actorRefOf[T])
        .toProvider(Providers.guicify(TypedAkka.typedProviderOf[T](behavior, name)))
        .asEagerSingleton()

  def bindTypedProvider[A: ClassTag, P <: Provider[ActorRef[A]] : ClassTag](): Unit =
    bindTypedProvider2[A, P](actorRefOf[A])
  def bindTypedProvider2[A, P <: Provider[ActorRef[A]] : ClassTag](actorRefOfA: TypeLiteral[ActorRef[A]]): Unit =
    bindTypedProvider3[A](actorRefOfA, classOfA[P])
  def bindTypedProvider3[A](actorRefOfA: TypeLiteral[ActorRef[A]], cls: Class[_ <: Provider[ActorRef[A]]]): Unit =
    accessBinder.bind(actorRefOfA).toProvider(cls).asEagerSingleton()

  private def accessBinder: Binder = { val method: Method = classOf[AbstractModule].getDeclaredMethod("binder"); if (!method.isAccessible) method.setAccessible(true); method.invoke(this).asInstanceOf[Binder] }
}

final class ScalaConfdActorProvider @Inject() (conf: Conf)
    extends TypedActorRefProvider[GetConf](new ScalaConfdActor(conf), "confd-actor4")

final class AppModule extends AbstractModule with AkkaTypedGuiceSupport {
  override def configure(): Unit = {
    bindTypedActor(FooActor(), "foo-actor2")
    bindTypedActor(HelloActor(), "hello-actor2")

    install(ConfdActor)
    bind(actorRefOf[GetConf]).toProvider(new TypedActorRefProvider2[GetConf]("confd-actor2")).asEagerSingleton()
//    bind(actorRefOf[GetConf]).toProvider(new TypedActorRefProvider3[GetConf]("confd-actor2") {
//      @Inject var behavior: Behavior[GetConf] = _
//    }).asEagerSingleton()
//    bind(actorRefOf[GetConf]).toProvider(new Provider[ActorRef[GetConf]] {
//      @Inject var system: ActorSystem         = _
//      @Inject var behavior: Behavior[GetConf] = _
//      def get(): ActorRef[GetConf] = system.spawn(behavior, "confd-actor2")
//    }).asEagerSingleton()

//    bind(actorRefOf[GetConf]).toProvider(classOf[ScalaConfdActorProvider]).asEagerSingleton()
//    bindTypedProvider3(actorRefOf[GetConf], classOf[ScalaConfdActorProvider])
//    bindTypedProvider2[GetConf, ScalaConfdActorProvider](actorRefOf[GetConf])
//    bindTypedProvider[GetConf, ScalaConfdActorProvider]
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
