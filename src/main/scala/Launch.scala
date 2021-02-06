package example

import example.server.store._
import zio._
import cats.effect.Timer

object App extends App {

  /**
   * An effect which, when executed, gives a StoreResource (capable of transforming a StoreHandler into something bindable)
   */
  val makeStoreResource: RIO[repository.Repository, server.store.StoreResource[RIO[repository.Repository, *]]] = {
    import zio.interop.catz._
    ZIO.runtime[repository.Repository].map { implicit r: Runtime[repository.Repository] =>
      new server.store.StoreResource[RIO[repository.Repository, *]]
    }
  }

  /**
   * Breaking out bindServer to keep noise fairly self-contained. This could consume
   * from some `Config` layer in order to access its port and host info.
   */
  def bindServer[R](httpApp: cats.data.Kleisli[RIO[R, *],org.http4s.Request[RIO[R, *]],org.http4s.Response[RIO[R, *]]]): ZManaged[R, Throwable, org.http4s.server.Server[RIO[R, *]]] = {
    import zio.interop.catz._
    import zio.interop.catz.implicits._

    import cats.effect._
    import cats.syntax.all._
    import org.http4s._
    import org.http4s.dsl.io._
    import org.http4s.implicits._
    import org.http4s.server.blaze.BlazeServerBuilder

    // Pardon the asInstanceOf, ioTimer has no way to inject R
    implicit val timer: cats.effect.Timer[RIO[R, *]] = ioTimer[Throwable].asInstanceOf[cats.effect.Timer[RIO[R, *]]]

    ZIO.runtime
      .toManaged_
      .flatMap { implicit r: Runtime[R] =>
        BlazeServerBuilder[RIO[R, *]]
          .bindHttp(8080, "localhost")
          .withHttpApp(httpApp)
          .resource
          .toManagedZIO
      }
  }


  /**
   * Our HTTP server implementation, utilizing the Repository Layer
   */
  val handler: server.store.StoreHandler[RIO[repository.Repository, *]] =
    new server.store.StoreHandler[RIO[repository.Repository, *]] {

      /**
       * getInventory
       *
       * Just grab from the repository
       *
       * Since we do not have multiple conflicting layers, we can just catchAll at the end to map errors
       */
      def getInventory(respond: GetInventoryResponse.type)(): RIO[repository.Repository,GetInventoryResponse] = (
        for {
          inventory <- repository.getInventory
        } yield respond.Ok(inventory)
      ).catchAll {
        case repository.StockroomUnavailable => UIO(respond.InternalServerError("Stockroom unavailable, please try again later"))
      }

      /**
       * placeOrder
       *
       * Grabbing optional fields from an optional body, so we mapError explicitly on every line to give a different example of error handling
       * repository.placeOrder also uses mapError, but translates from the repository error type into our error response.
       */
      def placeOrder(respond: PlaceOrderResponse.type)(body: Option[example.server.definitions.Order]): RIO[repository.Repository,PlaceOrderResponse] = (
        for {
          order <- ZIO.fromOption(body).mapError(_ => respond.MethodNotAllowed)
          id <- ZIO.fromOption(order.id).mapError(_ => respond.MethodNotAllowed)
          petId <- ZIO.fromOption(order.petId).mapError(_ => respond.MethodNotAllowed)
          quantity <- ZIO.fromOption(order.quantity).mapError(_ => respond.MethodNotAllowed)
          res <- repository.placeOrder(id, petId, quantity).mapError({ case repository.InsufficientQuantity(id) => respond.MethodNotAllowed }) // TODO: 405 isn't really applicable here
        } yield respond.Ok(res)
      ).merge // Second strategy of error handling, mapError to PlaceOrderResponses, then merge them all together

      /**
       * getOrderById
       *
       * If we had a whole bunch of conflicting error types from various layers, it may be useful to define a bespoke
       * error tree to keep the function body terse, without losing any specificity or totality in catchAll
       */
      sealed trait GetOrderByIdDownstreamErrors
      final case class GOBIRepoError(error: repository.GetOrderError) extends GetOrderByIdDownstreamErrors
      def getOrderById(respond: GetOrderByIdResponse.type)(orderId: Long): RIO[repository.Repository,GetOrderByIdResponse] = (
        for {
          order <- repository.getOrder(orderId).mapError(GOBIRepoError)
        } yield respond.Ok(order)
      ).catchAll { // Third strategy of error handling, mapping to a custom ADT to accept disparate downstream error types (pseudo union types/coproduct)
        case GOBIRepoError(repository.UnknownOrder(id)) => UIO(respond.NotFound)
      }

      /**
       * deleteOrder
       *
       * The underlying repository function call can fail with different errors, so mapError
       * those explicitly and use the .merge technique from placeOrder
       */
      def deleteOrder(respond: DeleteOrderResponse.type)(orderId: Long): RIO[repository.Repository,DeleteOrderResponse] = (
        for {
          () <- repository.deleteOrder(orderId).mapError {
            case repository.UnknownOrder(id) => respond.NotFound
            case repository.AlreadyDeleted(id) => respond.BadRequest
          }
        } yield respond.Accepted
      ).merge
    }

  def serveForever[R](httpRoutes: org.http4s.HttpRoutes[RIO[R, *]]): RIO[R, Nothing] = {
    import zio.interop.catz._
    import zio.interop.catz.implicits._

    import org.http4s.HttpRoutes
    import org.http4s.implicits._
    import org.http4s.server.blaze.BlazeServerBuilder

    type Z[A] = RIO[R, A]

    ZIO.runtime[R].flatMap { implicit r: Runtime[R] =>
      for {
        res <- bindServer(
          // This is quite unpleasant.
          // When using stable types, `Z` in this case, implicits resolve just fine, and we can use `.orNotFound` below.
          // When using kind-projector, however, as in `HttpRoutes[RIO[R, *]]`, resolution goes out
          // the window and nothing can resolve unless explicitly pinned.
          (httpRoutes: HttpRoutes[Z]).orNotFound
        ).use(_ => ZIO.never)
      } yield res
    }
  }

  def run(args: List[String]) = {
    import example.server.definitions.Order
    val initialInventory = Map(
      "Kibble" -> 10,
      "Treats" -> 3
    )

    val initialOrders = Map(
      123L -> Order(id = Some(123L), petId = Some(5L), quantity = Some(3), status = Some(Order.Status.Placed))
    )

    val inventoryLayer = Ref.make(initialInventory).toManaged_.toLayer
    val ordersLayer = Ref.make(initialOrders).toManaged_.toLayer
    (for {
      storeResource <- makeStoreResource
      res <- serveForever(storeResource.routes(handler))
    } yield res)
      .exitCode
      .provideSomeLayer[ZEnv]((inventoryLayer ++ ordersLayer) >>> repository.Repository.inMemory)
  }
}
