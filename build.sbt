name := "guardrail-sample-http4s-zio"
ThisBuild / organization := "se.hardchee"

ThisBuild / scalaVersion := "2.13.6"

// Convenience for cross-compat testing
ThisBuild / crossScalaVersions := Seq("2.12.14", "2.13.6")

val commonDependencies = Seq(
  // Depend on http4s-managed cats and circe
  "org.http4s"       %% "http4s-ember-client"   % "0.21.29",
  "org.http4s"       %% "http4s-ember-server"   % "0.21.29",
  "org.http4s"       %% "http4s-circe"          % "0.21.29",
  "org.http4s"       %% "http4s-dsl"            % "0.21.29",

  // ZIO and the interop library
  "dev.zio"          %% "zio"                   % "1.0.9",
  "dev.zio"          %% "zio-interop-cats"      % "2.5.1.0",
  "dev.zio"          %% "zio-test"              % "1.0.9" % "test",
  "dev.zio"          %% "zio-test-sbt"          % "1.0.9" % "test",
)

val commonSettings = Seq(
  scalacOptions ++= (if (scalaVersion.value.startsWith("2.12")) Seq("-Ypartial-unification") else Nil),

  // Use zio-test runner
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

  // Ensure canceling `run` releases socket, no matter what
  run / fork := true,

  // Better syntax for dealing with partially-applied types
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.0" cross CrossVersion.full),

  // Better semantics for for comprehensions
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

lazy val exampleServer = (project in file("example-server"))
  .settings(commonSettings)
  .settings(
    Compile / guardrailTasks += ScalaServer(file("server.yaml"), pkg="example.server", framework="http4s"),
    libraryDependencies ++= commonDependencies
  )
  .dependsOn(exampleClient % "test")

lazy val exampleClient = (project in file("example-client"))
  .settings(commonSettings)
  .settings(
    Compile / guardrailTasks += ScalaClient(file("server.yaml"), pkg="example.client", framework="http4s"),
    libraryDependencies ++= commonDependencies
  )

lazy val root = (project in file("."))
  .aggregate(exampleServer, exampleClient)
  .dependsOn(exampleServer, exampleClient)
