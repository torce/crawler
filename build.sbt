organization := "prototype"

name := "default"

version := "0.1-SNAPSHOT"

resolvers += "spray repo" at "http://repo.spray.io"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.2.1",
  "com.typesafe.akka" %% "akka-cluster" % "2.2.3",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3",
  "org.scalatest" %% "scalatest" % "2.0" % "test",
  "io.spray" % "spray-routing" % "1.2.0" % "test",
  "io.spray" % "spray-client" % "1.2.0",
  "org.rogach" %% "scallop" % "0.9.5")

atmosSettings