package example

import example.httpServer.Http4sServerLauncher
import example.repository.RepositoryService
import org.http4s.server.Server
import zio.logging.backend.SLF4J
import zio.{ExitCode, LogLevel, Runtime, Scope, ZIO, ZIOAppArgs, ZLayer}

object Launch extends zio.ZIOAppDefault {

  val logger = Runtime.removeDefaultLoggers >>> SLF4J.slf4j(LogLevel.Debug)

  case class ApplicationConfig(inMemory: Boolean, port: Int)

  def runWithConfig(config: ApplicationConfig): ZIO[Any with Scope, Throwable, Unit] = {

    val action: ZIO[Server, Nothing, Unit] = for {
      appRoutes <- ZIO.service[Server]
    } yield ()

    action.provideSome[Scope](
      ZLayer.succeed(config),
      Http4sServerLauncher.live,
      AppRoutes.live,
      StoreController.live,
      RepositoryService.live
    )
  }

  override def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] =
    ZIO.attempt(ApplicationConfig(inMemory = true, port = 8080)).orDie.flatMap { config =>
      ZIO.scoped {
        (runWithConfig(config) *> ZIO.debug("Application started correctly"))
          .catchAllCause(cause => ZIO.logError("Application failed to start" + cause.squash) as ExitCode.failure)
      }
    }.provide(logger) *> ZIO.never

}
