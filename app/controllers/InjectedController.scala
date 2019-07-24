package controllers

import java.lang.reflect.Method
import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.reflect.ClassTag
import akka.actor.{ Actor, ActorSystem, Props, typed }
import akka.actor.typed.{ ActorRef, Behavior, Scheduler }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors.{ receive, receiveMessage, setup }
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import com.google.inject.assistedinject.{ Assisted, FactoryModuleBuilder }
import com.google.inject.binder.LinkedBindingBuilder
import com.google.inject.name.Names
import com.google.inject.{ AbstractModule, Binder, TypeLiteral }
import com.google.inject.util.Providers
import javax.inject._
import scala.concurrent.duration._
import play.api.{ Configuration => Conf }
import play.api.inject.{ Injector, SimpleModule, bind }
import play.api.libs.concurrent.{ AkkaGuiceSupport, InjectedActorSupport }
import play.api.mvc._

// TODO: actor name
// TODO: annotatedWith(Names.named(name))
object Utils {
  def lookupConf(conf: Conf, key: String) = conf.getOptional[String](key).getOrElse("none")
  def rcv[A](onMessage: (ActorContext[A], A) => Unit) = receive[A] { case (ctx, msg) => onMessage(ctx, msg); Behaviors.same }
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

class TypedActorRefProvider[T](behavior: Behavior[T]) extends Provider[ActorRef[T]] {
  @Inject private var system: ActorSystem = _
  lazy val get: ActorRef[T] = system.spawnAnonymous(behavior)
}

object TypedAkka {
  def typedProviderOf[T](behavior: Behavior[T]) = new TypedActorRefProvider[T](behavior)
}

trait AkkaTypedGuiceSupport extends AkkaGuiceSupport { self: AbstractModule =>
  def bindTypedActor[T](tl: TypeLiteral[ActorRef[T]], behavior: Behavior[T]) = accessBinder.bind(tl).toProvider(Providers.guicify(TypedAkka.typedProviderOf[T](behavior))).asEagerSingleton()
  private def accessBinder: Binder = { val method: Method = classOf[AbstractModule].getDeclaredMethod("binder"); if (!method.isAccessible) method.setAccessible(true); method.invoke(this).asInstanceOf[Binder] }
}

@Singleton final class  ConfdActorProvider @Inject()(system: ActorSystem, conf: Conf)            extends Provider[ActorRef[GetConf]]  { def get() = system.spawn(ConfdActor(conf),          "confd-actor1") }
@Singleton final class ParentActorProvider @Inject()(system: ActorSystem, childFactory: MkChild) extends Provider[ActorRef[GetChild]] { def get() = system.spawn(ParentActor(childFactory), "parent-actor1") }

final class AppModule extends AbstractModule with AkkaTypedGuiceSupport {
  override def configure(): Unit = {
    bindTypedActor(new TypeLiteral[ActorRef[Event]]() {}, FooActor())
    bindTypedActor(new TypeLiteral[ActorRef[Greet]]() {}, HelloActor())
    bind(new TypeLiteral[ActorRef[GetConf]]() {}).toProvider(classOf[ConfdActorProvider]).asEagerSingleton()
//    bindTypedActor(ConfiguredActor(), "configured-actor")
    // bindActor[ParentActor]("parent-actor")
    // bind(new TypeLiteral[ActorRef[GetChild]]() {}).toProvider(classOf[ParentActorProvider]).asEagerSingleton()
    // bindActorFactory[ConfiguredChildActor, ConfiguredChildActor.Factory]
    // binder().install(new FactoryModuleBuilder().implement(new TypeLiteral[Behavior[GetChild]]() {}, ???).build(classOf[ConfiguredChildActor.Factory]))
  }
}

abstract class SharedController(cc: ControllerComponents) extends AbstractController(cc) {
  protected def system: ActorSystem // for the ask pattern
  protected def fooActor: ActorRef[Event]
  protected def helloActor: ActorRef[Greet]
  protected def confdActor: ActorRef[GetConf]

  implicit val timeout: Timeout             = 3.seconds
  implicit val scheduler: Scheduler         = system.toTyped.scheduler
  implicit val ec: ExecutionContextExecutor = system.toTyped.executionContext

  final def fireEvent  = Action { fooActor.tell(Event("a message")); Ok("actors were told a little secret") }
  final def greetings  = Action.async(helloActor.ask[String](Greet("Dale", _)).map(Ok(_)))
  final def lookupConf = Action.async(confdActor.ask[String](GetConf(_)).map(Ok(_)))
}

@Singleton final class InjectedController @Inject()(conf: Conf, cc: ControllerComponents, protected val system: ActorSystem,
    protected val fooActor: ActorRef[Event],
    protected val helloActor: ActorRef[Greet],
    protected val confdActor: ActorRef[GetConf],
) extends SharedController(cc)

@Singleton final class CompileDIController @Inject()(conf: Conf, cc: ControllerComponents, protected val system: ActorSystem) extends SharedController(cc) {
  val fooActor    = system.spawn(FooActor(),                            "foo-actor2")
  val helloActor  = system.spawn(HelloActor(),                          "hello-actor2")
  val confdActor  = system.spawn(ConfdActor(conf),                      "confd-actor2")
  val parentActor = system.spawn(ParentActor(ConfdChildActor(conf, _)), "parent-actor")

  def lookupConfViaParent = Action.async(parentActor.ask[ActorRef[GetConf]](GetChild("java.io.tmpdir", _)).flatMap(_.ask[String](GetConf(_))).map(Ok(_)))
}
