package example

import example.server.store._
import zio._
import cats.effect.Timer

object App extends App {

  // An effect which, when executed, gives a StoreResource (capable of transforming a StoreHandler into something bindable)
  val resource: RIO[repository.Repository, server.store.StoreResource[RIO[repository.Repository, *]]] = {
    import zio.interop.catz._
    ZIO.runtime[repository.Repository].map { implicit r: Runtime[repository.Repository] =>
      new server.store.StoreResource[RIO[repository.Repository, *]]
    }
  }

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


  // Our HTTP layer implementation, utilizing the Repository ZLayer
  val handler: server.store.StoreHandler[ZIO[repository.Repository, Throwable, *]] =
    new server.store.StoreHandler[ZIO[repository.Repository, Throwable, *]] {
      def getInventory(respond: GetInventoryResponse.type)(): zio.ZIO[repository.Repository,Nothing,GetInventoryResponse] = (
        for {
          inventory <- repository.getInventory
        } yield respond.Ok(inventory)
      ).catchAll { // First strategy of error handling, just catchAll
        case repository.StockroomUnavailable => UIO(respond.InternalServerError("Stockroom unavailable, please try again later"))
      }

      def placeOrder(respond: PlaceOrderResponse.type)(body: Option[example.server.definitions.Order]): zio.ZIO[repository.Repository,Nothing,PlaceOrderResponse] = (
        for {
          order <- ZIO.fromOption(body).mapError(_ => respond.MethodNotAllowed)
          id <- ZIO.fromOption(order.id).mapError(_ => respond.MethodNotAllowed)
          petId <- ZIO.fromOption(order.petId).mapError(_ => respond.MethodNotAllowed)
          quantity <- ZIO.fromOption(order.quantity).mapError(_ => respond.MethodNotAllowed)
          res <- repository.placeOrder(id, petId, quantity).mapError({ case repository.InsufficientQuantity(id) => respond.MethodNotAllowed }) // TODO: 405 isn't really applicable here
        } yield respond.Ok(res)
      ).merge // Second strategy of error handling, mapError to PlaceOrderResponses, then merge them all together

      sealed trait GetOrderByIdDownstreamErrors
      final case class GOBIRepoError(error: repository.GetOrderError) extends GetOrderByIdDownstreamErrors
      def getOrderById(respond: GetOrderByIdResponse.type)(orderId: Long): zio.ZIO[repository.Repository,Nothing,GetOrderByIdResponse] = (
        for {
          order <- repository.getOrder(orderId).mapError(GOBIRepoError)
        } yield respond.Ok(order)
      ).catchAll { // Third strategy of error handling, mapping to a custom ADT to accept disparate downstream error types (pseudo union types/coproduct)
        case GOBIRepoError(repository.UnknownOrder(id)) => UIO(respond.NotFound)
      }

      def deleteOrder(respond: DeleteOrderResponse.type)(orderId: Long): zio.ZIO[repository.Repository,Nothing,DeleteOrderResponse] = (
        for {
          () <- repository.deleteOrder(orderId).mapError {
            case repository.UnknownOrder(id) => respond.NotFound
            case repository.AlreadyDeleted(id) => respond.BadRequest
          }
        } yield respond.Accepted
      ).merge
    }

  val serveForever: RIO[repository.Repository, Nothing] = {
    import zio.interop.catz._
    import zio.interop.catz.implicits._

    import org.http4s.implicits._
    import org.http4s.server.blaze.BlazeServerBuilder

    ZIO.runtime[repository.Repository].flatMap { implicit r: Runtime[repository.Repository] =>
      for {
        r <- resource
        httpRoutes = r.routes(handler)
        res <- bindServer(http4sKleisliResponseSyntaxOptionT[RIO[repository.Repository, *], org.http4s.Request[RIO[repository.Repository, *]]](httpRoutes).orNotFound).use(_ => ZIO.never)
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
    serveForever
      .exitCode
      .provideSomeLayer[ZEnv]((inventoryLayer ++ ordersLayer) >>> repository.Repository.inMemory)
  }
}
