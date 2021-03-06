import scoverage.ScoverageSbtPlugin._

organization := "es.udc"

name := "scrawl"

version := "0.1-SNAPSHOT"

resolvers ++= Seq(
  "spray repo" at "http://repo.spray.io",
  "sprouch repo" at "http://kimstebel.github.com/sprouch/repository",
  Classpaths.sbtPluginReleases)

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

scalacOptions in (Compile, doc) += "-diagrams"

parallelExecution in ScoverageTest := false

ScoverageKeys.highlighting := true

libraryDependencies ++= Seq(
  "sprouch" %% "sprouch" %"0.5.11-custom",
  "com.typesafe.akka" %% "akka-contrib" % "2.2.1",
  "com.typesafe.akka" %% "akka-cluster" % "2.2.3",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3",
  "org.ccil.cowan.tagsoup" % "tagsoup" % "1.2",
  "org.scalatest" %% "scalatest" % "2.0" % "test",
  "io.spray" % "spray-routing" % "1.2.0" % "test",
  "io.spray" % "spray-client" % "1.2.0",
  "io.spray" %% "spray-json" % "1.2.6",
  "org.rogach" %% "scallop" % "0.9.5")

atmosSettings

instrumentSettings
