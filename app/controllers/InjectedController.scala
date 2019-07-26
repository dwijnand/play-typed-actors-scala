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
import com.google.inject.util.Providers
import com.google.inject.{ AbstractModule, Binder, TypeLiteral }
import java.lang.reflect.Method
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
  def classOf[A: ClassTag](): Class[A] = classTag[A].runtimeClass.asInstanceOf
  def lookupConf(conf: Conf, key: String) = conf.getOptional[String](key).getOrElse("none")
  def rcv[A](onMessage: (Ctx[A], A) => Unit) = receive[A] { case (ctx, msg) => onMessage(ctx, msg); Behaviors.same }
}; import Utils._

final case class Event(name: String)
final case class Greet(name: String, replyTo: ActorRef[String])
final case class GetConf(replyTo: ActorRef[String])
final case class GetChild(key: String, replyTo: ActorRef[ActorRef[GetConf]])
trait MkChild { def apply(key: String): Behavior[GetConf] }
object        FooActor {           def apply()                                  = rcv[Event]    { case (ctx, msg)                     => println(s"foo => ${msg.name}")         } }
object      HelloActor {           def apply()                                  = rcv[Greet]    { case (ctx, Greet(name, replyTo))    => replyTo ! s"Hello, $name"              } }
object      ConfdActor { @Inject() def apply(conf: Conf)                        = rcv[GetConf]  { case (ctx, GetConf(replyTo))        => replyTo ! lookupConf(conf, "my.cfg")   } }
object ConfdChildActor { @Inject() def apply(conf: Conf, @Assisted key: String) = rcv[GetConf]  { case (ctx, GetConf(replyTo))        => replyTo ! lookupConf(conf, key)        } }
object     ParentActor { @Inject() def apply(mkChild: MkChild)                  = rcv[GetChild] { case (ctx, GetChild(key, replyTo))  => replyTo ! ctx.spawn(mkChild(key), key) } }
final class        ScalaFooActor                                        extends scaladsl.AbstractBehavior[Event]    { def onMessage(msg: Event)    = { println(s"foo => ${msg.name}")                     ; this } }
final class      ScalaHelloActor                                        extends scaladsl.AbstractBehavior[Greet]    { def onMessage(msg: Greet)    = { msg.replyTo ! s"Hello, ${msg.name}"                ; this } }
final class      ScalaConfdActor(conf: Conf)                            extends scaladsl.AbstractBehavior[GetConf]  { def onMessage(msg: GetConf)  = { msg.replyTo ! lookupConf(conf, "my.cfg")           ; this } }
final class ScalaConfdChildActor(conf: Conf, @Assisted key: String)     extends scaladsl.AbstractBehavior[GetConf]  { def onMessage(msg: GetConf)  = { msg.replyTo ! lookupConf(conf, key)                ; this } }
final class     ScalaParentActor(mkChild: MkChild, ctx: Ctx[GetChild])  extends scaladsl.AbstractBehavior[GetChild] { def onMessage(msg: GetChild) = { msg.replyTo ! ctx.spawn(mkChild(msg.key), msg.key) ; this } }
final class         JavaFooActor                                        extends  javadsl.AbstractBehavior[Event]    { def createReceive = newReceiveBuilder.onAnyMessage { msg => println(s"foo => ${msg.name}")                                 ; this }.build() }
final class       JavaHelloActor                                        extends  javadsl.AbstractBehavior[Greet]    { def createReceive = newReceiveBuilder.onAnyMessage { case Greet(name, replyTo)   => replyTo ! s"Hello, $name"              ; this }.build() }
final class       JavaConfdActor(conf: Conf)                            extends  javadsl.AbstractBehavior[GetConf]  { def createReceive = newReceiveBuilder.onAnyMessage { case GetConf(replyTo)       => replyTo ! lookupConf(conf, "my.cfg")   ; this }.build() }
final class  JavaConfdChildActor(conf: Conf, @Assisted key: String)     extends  javadsl.AbstractBehavior[GetConf]  { def createReceive = newReceiveBuilder.onAnyMessage { case GetConf(replyTo)       => replyTo ! lookupConf(conf, key)        ; this }.build() }
final class      JavaParentActor(mkChild: MkChild, ctx: JCtx[GetChild]) extends  javadsl.AbstractBehavior[GetChild] { def createReceive = newReceiveBuilder.onAnyMessage { case GetChild(key, replyTo) => replyTo ! ctx.spawn(mkChild(key), key) ; this }.build() }

class TypedActorRefProvider[T](behavior: Behavior[T], name: String) extends Provider[ActorRef[T]] {
  @Inject private var system: ActorSystem = _
  lazy val get: ActorRef[T] = system.spawn(behavior, name)
}

object TypedAkka {
  def typedProviderOf[T](behavior: Behavior[T], name: String) = new TypedActorRefProvider[T](behavior, name)
}

trait AkkaTypedGuiceSupport extends AkkaGuiceSupport { self: AbstractModule =>
  def bindTypedActor[T](tl: TypeLiteral[ActorRef[T]], behavior: Behavior[T], name: String) = accessBinder.bind(tl).toProvider(Providers.guicify(TypedAkka.typedProviderOf[T](behavior, name))).asEagerSingleton()
  private def accessBinder: Binder = { val method: Method = classOf[AbstractModule].getDeclaredMethod("binder"); if (!method.isAccessible) method.setAccessible(true); method.invoke(this).asInstanceOf[Binder] }
}

final class      ConfdActorProvider @Inject()(system: ActorSystem, conf: Conf)            extends Provider[ActorRef[GetConf]]  { def get() = system.spawn(ConfdActor(conf),          "confd-actor2")  }
final class ScalaConfdActorProvider @Inject()(system: ActorSystem, conf: Conf)            extends Provider[ActorRef[GetConf]]  { def get() = system.spawn(new ScalaConfdActor(conf), "confd-actor4")  }
final class         MkChildProvider @Inject()(system: ActorSystem, conf: Conf)            extends Provider[MkChild]            { def get() = ConfdChildActor(conf, _)                                 }
final class     ParentActorProvider @Inject()(system: ActorSystem, childFactory: MkChild) extends Provider[ActorRef[GetChild]] { def get() = system.spawn(ParentActor(childFactory), "parent-actor2") }

final class AppModule extends AbstractModule with AkkaTypedGuiceSupport {
  override def configure(): Unit = {
    bindTypedActor(new TypeLiteral[ActorRef[Event]]() {}, FooActor(), "foo-actor2")
    bindTypedActor(new TypeLiteral[ActorRef[Greet]]() {}, HelloActor(), "hello-actor2")
//    bind(new TypeLiteral[ActorRef[GetConf]]() {}).toProvider(classOf[ConfdActorProvider]).asEagerSingleton()
    bind(new TypeLiteral[ActorRef[GetConf]]() {}).toProvider(classOf[ScalaConfdActorProvider]).asEagerSingleton()
    bind(classOf[MkChild]).toProvider(classOf[MkChildProvider]).asEagerSingleton()
    bind(new TypeLiteral[ActorRef[GetChild]]() {}).toProvider(classOf[ParentActorProvider]).asEagerSingleton()
    // bindActorFactory[ConfdChildActor, MkChild]
    // binder().install(new FactoryModuleBuilder().implement(new TypeLiteral[Behavior[GetChild]]() {}, ???).build(classOf[ConfiguredChildActor.Factory]))
  }
}

abstract class SharedController(cc: ControllerComponents) extends AbstractController(cc) {
  protected def system: ActorSystem // for the ask pattern
  protected def fooActor: ActorRef[Event]
  protected def helloActor: ActorRef[Greet]
  protected def confdActor: ActorRef[GetConf]
  protected def parentActor: ActorRef[GetChild]

  implicit val timeout: Timeout             = 3.seconds                       // for ask
  implicit val scheduler: Scheduler         = system.toTyped.scheduler        // for ask
  implicit val ec: ExecutionContextExecutor = system.toTyped.executionContext // for Future map

  final def fireEvent  = Action {  fooActor.tell(Event("a message"));   Ok("fired event")  }
  final def greetings  = Action.async(for (rsp <- helloActor.ask[String](Greet("Dale", _))) yield Ok(rsp))
  final def lookupConf = Action.async(for (rsp <- confdActor.ask[String](GetConf(_))) yield Ok(rsp))
  final def lookupConfViaParent = Action.async {
    for {
      childActor <- parentActor.ask[ActorRef[GetConf]](GetChild("java.io.tmpdir", _))
      rsp <- childActor.ask[String](GetConf(_))
    } yield Ok(rsp)
  }
}

final class AController @Inject()(conf: Conf, cc: ControllerComponents, protected val system: ActorSystem) extends SharedController(cc) {
  val fooActor    = system.spawn(FooActor(),                            "foo-actor1")
  val helloActor  = system.spawn(HelloActor(),                          "hello-actor1")
  val confdActor  = system.spawn(ConfdActor(conf),                      "confd-actor1")
  val confdActor3 = system.spawn(new ScalaConfdActor(conf),             "confd-actor3")
  val parentActor = system.spawn(ParentActor(ConfdChildActor(conf, _)), "parent-actor1")
}

final class BController @Inject()(conf: Conf, cc: ControllerComponents, protected val system: ActorSystem,
    protected val fooActor: ActorRef[Event],
    protected val helloActor: ActorRef[Greet],
    protected val confdActor: ActorRef[GetConf],
    protected val parentActor: ActorRef[GetChild],
) extends SharedController(cc)
