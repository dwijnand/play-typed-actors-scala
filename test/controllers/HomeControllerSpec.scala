package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.test._
import play.api.test.Helpers._

// https://www.playframework.com/documentation/latest/ScalaTestingWithScalaTest
class HomeControllerSpec extends PlaySpec with GuiceOneAppPerTest with Injecting {

  "HomeController" should {

    "spawn" in {
      val controller = inject[HomeController]
      controller mustBe a [HomeController]
    }

  }
}
