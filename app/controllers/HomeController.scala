package controllers

import scala.reflect.ClassTag
import akka.actor.{ Actor, ActorSystem, typed }
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import com.google.inject.assistedinject.{ Assisted, FactoryModuleBuilder }
import com.google.inject.{ AbstractModule, TypeLiteral }
import javax.inject._
import play.api.Configuration
import play.api.inject.Injector
import play.api.libs.concurrent.{ AkkaGuiceSupport, InjectedActorSupport }
import play.api.mvc._

object HelloActor {
  final case class SayHello(name: String, replyTo: ActorRef[String])

  def apply(): Behavior[SayHello] = Behaviors.receiveMessage {
    case SayHello(name, replyTo) =>
      replyTo ! s"Hello, $name"
      Behaviors.same
  }
}

object ConfiguredActor {
  final case class GetConfig(replyTo: ActorRef[String])

  @Inject() def apply(conf: Configuration): Behavior[GetConfig] = {
    val config = conf.getOptional[String]("my.config").getOrElse("none")
    Behaviors.receiveMessage {
      case GetConfig(replyTo) =>
        replyTo ! config
        Behaviors.same
    }
  }
}

object ConfiguredChildActor {
  final case class GetConfig(replyTo: ActorRef[String])

  @Inject() def apply(conf: Configuration, @Assisted key: String): Behavior[GetConfig] = {
    val config = conf.getOptional[String](key).getOrElse("none")
    Behaviors.receiveMessage {
      case GetConfig(replyTo) =>
        replyTo ! config
        Behaviors.same
    }
  }

  trait Factory {
    def apply(key: String): Behavior[GetConfig]
  }
}

object ParentActor {
  final case class GetChild(key: String, replyTo: ActorRef[ActorRef[ConfiguredChildActor.GetConfig]])

  @Inject() def apply(childFactory: ConfiguredChildActor.Factory): Behavior[GetChild] = Behaviors.receive {
    case (ctx, GetChild(key, replyTo)) =>
      val child: ActorRef[ConfiguredChildActor.GetConfig] = ctx.spawn(childFactory(key), key)
      replyTo ! child
      Behaviors.same
  }
}

@Singleton
final class TypedActorSystemProvider @Inject()(actorSystem: ActorSystem)
    extends Provider[typed.ActorSystem[Nothing]] {
  lazy val get = actorSystem.toTyped
}

final class TypedActorSystemModule extends AbstractModule {
  override def configure(): Unit = {
    bind(new TypeLiteral[typed.ActorSystem[Nothing]]() {})
        .toProvider(classOf[TypedActorSystemProvider])
        .asEagerSingleton()
  }
}

object FooActor {
  final case class Event(name: String)

  def apply(): Behavior[Event] =
    Behaviors.receiveMessage { msg =>
      println(s"foo => ${msg.name}")
      Behaviors.same
    }
}

class TypedActorRefProvider[T: ClassTag]() extends Provider[ActorRef[T]] {
  @Inject private var actorSystem: ActorSystem = _
  @Inject private var injector: Injector       = _

  lazy val get: ActorRef[T] = {
//    val creation = Props(injector.instanceOf[T])
//    actorSystem.actorOf(props(creation), name)
    ???
  }
}

object TypedAkka {

  def typedProviderOf[T: ClassTag]: Provider[ActorRef[T]] = new TypedActorRefProvider[T]

//  /**
//   * Create a binding for an actor implemented by the given class, with the given name.
//   *
//   * This will instantiate the actor using Play's injector, allowing it to be dependency injected itself.  The returned
//   * binding will provide the ActorRef for the actor, qualified with the given name, allowing it to be injected into
//   * other components.
//   *
//   * Example usage from a Play module:
//   * {{{
//   * def bindings = Seq(
//   *   Akka.bindingOf[MyActor]("myActor"),
//   *   ...
//   * )
//   * }}}
//   *
//   * Then to use the above actor in your application, add a qualified injected dependency, like so:
//   * {{{
//   *   class MyController @Inject() (@Named("myActor") myActor: ActorRef,
//   *      val controllerComponents: ControllerComponents) extends BaseController {
//   *     ...
//   *   }
//   * }}}
//   *
//   * @param name  The name of the actor.
//   * @param props A function to provide props for the actor. The props passed in will just describe how to create the
//   *              actor, this function can be used to provide additional configuration such as router and dispatcher
//   *              configuration.
//   * @tparam T The class that implements the actor.
//   * @return A binding for the actor.
//   */
//  def bindingOf[T <: Actor: ClassTag](name: String, props: Props => Props = identity): Binding[ActorRef] =
//    bind[ActorRef].qualifiedWith(name).to(providerOf[T](name, props)).eagerly()
}

trait AkkaTypedGuiceSupport extends AkkaGuiceSupport { self: AbstractModule =>
  import com.google.inject.util.Providers

  def bindTypedActor[T: ClassTag]: Unit = {
    binder
        .bind(new TypeLiteral[ActorRef[T]]() {})
        .toProvider(Providers.guicify(TypedAkka.typedProviderOf[T]))
        .asEagerSingleton()
  }
}

@Singleton final class FooActorProvider @Inject()(actorSystem: ActorSystem)
    extends Provider[ActorRef[FooActor.Event]] {
  def get() = actorSystem.spawn(FooActor(), "foo-actor")
}

@Singleton final class ConfiguredActorProvider @Inject()(
    actorSystem: ActorSystem,
    conf: Configuration,
) extends Provider[ActorRef[ConfiguredActor.GetConfig]] {
  def get() = actorSystem.spawn(ConfiguredActor(conf), "configured-actor")
}

@Singleton final class ParentActorProvider @Inject()(
    actorSystem: ActorSystem,
    childFactory: ConfiguredChildActor.Factory,
) extends Provider[ActorRef[ParentActor.GetChild]] {
  def get() = actorSystem.spawn(ParentActor(childFactory), "parent-actor")
}

final class FooActorModule extends AbstractModule with AkkaTypedGuiceSupport {
  override def configure(): Unit = {
    bind(new TypeLiteral[ActorRef[FooActor.Event]]() {})
        .toProvider(classOf[FooActorProvider])
        .asEagerSingleton()
    bind(new TypeLiteral[ActorRef[ConfiguredActor.GetConfig]]() {})
        .toProvider(classOf[ConfiguredActorProvider])
        .asEagerSingleton()
    bind(new TypeLiteral[ActorRef[ParentActor.GetChild]]() {})
        .toProvider(classOf[ParentActorProvider])
        .asEagerSingleton()
    bind(new TypeLiteral[ActorRef[ParentActor.GetChild]]() {})
        .toProvider(classOf[ParentActorProvider])
        .asEagerSingleton()
    binder().install(
      new FactoryModuleBuilder()
          .implement(
            new TypeLiteral[Behavior[ParentActor.GetChild]]() {},
            new
          )
          .build(classOf[ConfiguredChildActor.Factory])
    )

    /*
    bindActor[ParentActor]("parent-actor")
    bindActorFactory[ConfiguredChildActor, ConfiguredChildActor.Factory]
     */

  }
}

@Singleton class HomeController @Inject()(
    cc: ControllerComponents,
    actorSystem: ActorSystem,
    typedActorSystem: typed.ActorSystem[Nothing],
    fooActor: ActorRef[FooActor.Event],
    configuredActor: ActorRef[ConfiguredActor.GetConfig],
) extends AbstractController(cc) {
  val helloActor: ActorRef[HelloActor.SayHello] = actorSystem.spawn(HelloActor(), "hello-actor")

  def action = Action {
    fooActor.tell(FooActor.Event("a message"))
    Ok("actors were told a little secret")
  }
}

@Singleton
class Application @Inject()(system: ActorSystem, cc: ControllerComponents) extends AbstractController(cc) {


}
