val `play-typed-actors-scala` = project in file(".")

organization := "com.lightbend"
version := "1.0-SNAPSHOT"

enablePlugins(PlayScala)

scalaVersion := "2.13.0"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0-M3" % Test

// https://search.maven.org/search?q=g:com.typesafe.akka%20AND%20a:akka-actor-typed_2.12&core=gav
libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % "2.6.0-M4"
