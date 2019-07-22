package controllers

import actors.Event
import akka.actor.typed.ActorRef
import javax.inject._
import play.api.mvc._

@Singleton
class HomeController @Inject()(cc: ControllerComponents, eventActor: ActorRef[Event]) extends AbstractController(cc) {
  def action = Action {
    eventActor.tell(Event("a message"))
    Ok("actors were told a little secret")
  }
}
