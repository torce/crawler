organization := "prototype"

name := "default"

version := "0.1-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.2.1",
  "com.typesafe.akka" %% "akka-cluster" % "2.2.3",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3",
  "org.scalatest" %% "scalatest" % "2.0" % "test")

atmosSettings