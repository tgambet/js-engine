organization := "com.typesafe"

name := "jse"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.10.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.2",
  "com.typesafe.akka" %% "akka-contrib" % "2.3.2",
  "io.apigee.trireme" % "trireme-core" % "0.7.3",
  "io.apigee.trireme" % "trireme-node10src" % "0.7.3",
  "io.spray" %% "spray-json" % "1.2.6",
  "org.slf4j" % "slf4j-simple" % "1.7.7",
  "org.specs2" %% "specs2" % "2.3.11" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.2" % "test"
)

resolvers += Resolver.sonatypeRepo("snapshots")

publishTo := {
    val typesafe = "http://private-repo.typesafe.com/typesafe/"
    val (name, url) = if (isSnapshot.value)
                        ("sbt-plugin-snapshots", typesafe + "maven-snapshots")
                      else
                        ("sbt-plugin-releases", typesafe + "maven-releases")
    Some(Resolver.url(name, new URL(url)))
}

lazy val root = project in file(".")

lazy val `js-engine-tester` = project.dependsOn(root)

