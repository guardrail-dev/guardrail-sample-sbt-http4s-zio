name := "guardrail-sample-http4s-zio"
organization := "se.hardchee"

scalaVersion := "2.13.5"

// Just for show
crossScalaVersions := Seq("2.12.13", "2.13.5")

libraryDependencies ++= Seq(
  // Depend on http4s, which will pull in cats and circe
  "org.http4s"       %% "http4s-ember-client"   % "0.21.22",
  "org.http4s"       %% "http4s-ember-server"   % "0.21.22",
  "org.http4s"       %% "http4s-circe"          % "0.21.22",
  "org.http4s"       %% "http4s-dsl"            % "0.21.22",

  // ZIO and the interop library
  "dev.zio"          %% "zio"                   % "1.0.7",
  "dev.zio"          %% "zio-interop-cats"      % "2.4.1.0",
)

// Ensure canceling `run` releases socket, no matter what
run / fork := true

// Better syntax for dealing with partially-applied types
addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.3" cross CrossVersion.full)

// Better semantics for for comprehensions
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

// Server config
Compile / guardrailTasks := List(
  ScalaServer(file("server.yaml"), pkg="example.server", framework="http4s"),
)

// Client config for tests
Test / guardrailTasks := List(
  ScalaClient(file("server.yaml"), pkg="example.client", framework="http4s"),
)

scalacOptions ++= (if (scalaVersion.value.startsWith("2.12")) Seq("-Ypartial-unification") else Nil)
