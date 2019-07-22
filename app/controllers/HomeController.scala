package controllers

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import com.google.inject.{ AbstractModule, TypeLiteral }
import javax.inject._
import play.api.mvc._

object FooActor {
  final case class Event(name: String)

  def apply(): Behavior[Event] =
    Behaviors.receiveMessage { msg =>
      println(s"foo => ${msg.name}")
      Behaviors.same
    }
}

class FooActorProvider @Inject()(actorSystem: ActorSystem) extends Provider[ActorRef[FooActor.Event]] {
  def get() = actorSystem.spawn(FooActor(), "foo-actor")
}

class FooActorModule extends AbstractModule {
  override def configure(): Unit = {
    bind(new TypeLiteral[ActorRef[FooActor.Event]]() {})
        .toProvider(classOf[FooActorProvider])
        .asEagerSingleton()
  }
}

@Singleton
class HomeController @Inject()(cc: ControllerComponents, fooActor: ActorRef[FooActor.Event]) extends AbstractController(cc) {
  def action = Action {
    fooActor.tell(FooActor.Event("a message"))
    Ok("actors were told a little secret")
  }
}
