package controllers

import java.lang.reflect.Method
import scala.reflect.ClassTag
import akka.actor.{ Actor, ActorSystem, Props, typed }
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import com.google.inject.assistedinject.{ Assisted, FactoryModuleBuilder }
import com.google.inject.binder.LinkedBindingBuilder
import com.google.inject.name.Names
import com.google.inject.{ AbstractModule, Binder, TypeLiteral }
import com.google.inject.util.Providers
import javax.inject._
import play.api.Configuration
import play.api.inject.{ Injector, SimpleModule, bind }
import play.api.libs.concurrent.{ AkkaGuiceSupport, InjectedActorSupport }
import play.api.mvc._

// TODO: actor name
// TODO: annotatedWith(Names.named(name))
object Utils {
  def lookupConf(conf: Configuration, key: String) = conf.getOptional[String](key).getOrElse("none")
}; import Utils._

@Singleton final class TypedActorSystemProvider @Inject()(system: ActorSystem) extends Provider[typed.ActorSystem[Nothing]] { lazy val get = system.toTyped }
//class BuiltinModule2 extends SimpleModule((_, _) => Seq(bind[typed.ActorSystem[Nothing]].toProvider[TypedActorSystemProvider]))
final class TypedActorSystemModule extends AbstractModule {
  override def configure() = {
    bind(new TypeLiteral[typed.ActorSystem[Nothing]] {}).toProvider(classOf[TypedActorSystemProvider]).asEagerSingleton()
  }
}

final case class Event(name: String)
final case class SayHello(name: String, replyTo: ActorRef[String])
final case class GetConfig(replyTo: ActorRef[String])
final case class GetChild(key: String, replyTo: ActorRef[ActorRef[GetConfig]])
object           HelloActor {           def apply()                                           = { Behaviors.receiveMessage[SayHello]  { case SayHello(name, replyTo) => replyTo ! s"Hello, $name";                   Behaviors.same } } }
object      ConfiguredActor { @Inject() def apply(conf: Configuration)                        = { Behaviors.receiveMessage[GetConfig] { case GetConfig(replyTo)      => replyTo ! lookupConf(conf, "my.config");     Behaviors.same } } }
object ConfiguredChildActor { @Inject() def apply(conf: Configuration, @Assisted key: String) = { Behaviors.receiveMessage[GetConfig] { case GetConfig(replyTo)      => replyTo ! lookupConf(conf, key);             Behaviors.same } }; trait Factory { def apply(key: String): Behavior[GetConfig] } }
object          ParentActor { @Inject() def apply(childFactory: ConfiguredChildActor.Factory) = { Behaviors.receive[GetChild]   { case (ctx, GetChild(key, replyTo)) => replyTo ! ctx.spawn(childFactory(key), key); Behaviors.same } } }
object             FooActor {           def apply()                                           = { Behaviors.receiveMessage[Event]     { msg                          => println(s"foo => ${msg.name}");              Behaviors.same } } }

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

@Singleton final class        FooActorProvider @Inject()(system: ActorSystem)                                             extends Provider[ActorRef[Event]]     { def get() = system.spawn(FooActor(), "foo-actor") }
@Singleton final class ConfiguredActorProvider @Inject()(system: ActorSystem, conf: Configuration)                        extends Provider[ActorRef[GetConfig]] { def get() = system.spawn(ConfiguredActor(conf), "configured-actor") }
@Singleton final class     ParentActorProvider @Inject()(system: ActorSystem, childFactory: ConfiguredChildActor.Factory) extends Provider[ActorRef[GetChild]]  { def get() = system.spawn(ParentActor(childFactory), "parent-actor") }

final class AppModule extends AbstractModule with AkkaTypedGuiceSupport {
  override def configure(): Unit = {
//    bind(new TypeLiteral[ActorRef[Event]]() {}).toProvider(classOf[FooActorProvider]).asEagerSingleton()
    bindTypedActor(new TypeLiteral[ActorRef[Event]]() {}, FooActor())
    bind(new TypeLiteral[ActorRef[GetConfig]]() {}).toProvider(classOf[ConfiguredActorProvider]).asEagerSingleton()
//    bindTypedActor(ConfiguredActor(), "configured-actor")
    // bindActor[ParentActor]("parent-actor")
    // bind(new TypeLiteral[ActorRef[GetChild]]() {}).toProvider(classOf[ParentActorProvider]).asEagerSingleton()
    // bindActorFactory[ConfiguredChildActor, ConfiguredChildActor.Factory]
    // binder().install(new FactoryModuleBuilder().implement(new TypeLiteral[Behavior[GetChild]]() {}, ???).build(classOf[ConfiguredChildActor.Factory]))
  }
}

@Singleton class HomeController @Inject()(
    conf: Configuration,
    cc: ControllerComponents,
    system: ActorSystem,
    typedActorSystem: typed.ActorSystem[Nothing],
    fooActor: ActorRef[Event],
    configuredActor: ActorRef[GetConfig],
) extends AbstractController(cc) {
  val helloActor: ActorRef[SayHello] = system.spawn(HelloActor(), "hello-actor")

  val compileDi__configuredActor: ActorRef[GetConfig] =
    system.spawn(ConfiguredActor(conf), "compile-di__configured-actor")

  def action = Action { fooActor.tell(Event("a message")); Ok("actors were told a little secret") }
}
