package controllers

import com.google.inject.assistedinject.{ AssistedInjectBinding, AssistedInjectTargetVisitor }
import com.google.inject.spi.DefaultBindingTargetVisitor
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.test._
import play.api.test.Helpers._

// https://www.playframework.com/documentation/latest/ScalaTestingWithScalaTest
class HomeControllerSpec extends PlaySpec with GuiceOneAppPerTest with Injecting {

  "HomeController" should {

    "spawn a" in {
      val controller = inject[BController]
      controller mustBe a [BController]
    }

    "spawn b" in {
      val controller = inject[AController]
      controller mustBe a [AController]
    }

    "injector" in {
      val _ = inject[com.google.inject.Injector]
      1 mustBe 1
    }

  }
}
