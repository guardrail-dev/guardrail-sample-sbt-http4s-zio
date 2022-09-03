
val Versions = new {
  val Http4s = "0.23.14"
  val Zio = "2.0.0"
  val ZioCatsInterop = "3.3.0"
}


name := "guardrail-sample-http4s-zio"
ThisBuild / organization := "se.hardchee"

ThisBuild / scalaVersion := "3.1.3"

ThisBuild / crossScalaVersions := Seq("2.12.14", "2.13.6", "3.1.3")

val commonDependencies = Seq(
  // Depend on http4s-managed cats and circe
  "org.http4s" %% "http4s-armeria-server" % "0.5.0",
  "org.http4s" %% "http4s-armeria-client" % "0.5.0",
  "org.http4s" %% "http4s-circe" % Versions.Http4s,
  "org.http4s" %% "http4s-dsl" % Versions.Http4s,
  "dev.zio" %% "zio-logging-slf4j" % Versions.Zio,

  // ZIO and the interop library
  "dev.zio" %% "zio" % Versions.Zio,
  "dev.zio" %% "zio-interop-cats" % Versions.ZioCatsInterop,
  "dev.zio" %% "zio-test" % Versions.Zio % "test",
  "dev.zio" %% "zio-test-sbt" % Versions.Zio % "test",
)

val commonSettings = Seq(
  scalacOptions ++= (if (scalaVersion.value.startsWith("2.12")) Seq("-Ypartial-unification") else Nil),

  // Use zio-test runner
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

  // Ensure canceling `run` releases socket, no matter what
  run / fork := true,

  // Better syntax for dealing with partially-applied types
  libraryDependencies ++= {
    scalaVersion.value match {
      case v if v.startsWith("3") =>
        Nil
      case _ =>
        List(
          compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.0" cross CrossVersion.full),
          compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
        )
    }
  }

)

lazy val exampleServer = (project in file("example-server"))
  .settings(commonSettings)
  .settings(
    Compile / guardrailTasks += ScalaServer(file("server.yaml"), pkg = "example.server", framework = "http4s"),
    libraryDependencies ++= commonDependencies
  )
  .dependsOn(exampleClient % "test")

lazy val exampleClient = (project in file("example-client"))
  .settings(commonSettings)
  .settings(
    Compile / guardrailTasks += ScalaClient(file("server.yaml"), pkg = "example.client", framework = "http4s"),
    libraryDependencies ++= commonDependencies
  )

lazy val root = (project in file("."))
  .aggregate(exampleServer, exampleClient)
  .dependsOn(exampleServer, exampleClient)
