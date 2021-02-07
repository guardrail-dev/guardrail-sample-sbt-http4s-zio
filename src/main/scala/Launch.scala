package example

import zio._

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
   * Our HTTP server implementation, utilizing the Repository Layer
   */
  val handler: server.store.StoreHandler[RIO[repository.Repository, *]] =
    new server.store.StoreHandler[RIO[repository.Repository, *]] {
      import example.server.store._

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
          order <- ZIO.fromOption(body).orElseFail(respond.MethodNotAllowed)
          id <- ZIO.fromOption(order.id).orElseFail(respond.MethodNotAllowed)
          petId <- ZIO.fromOption(order.petId).orElseFail(respond.MethodNotAllowed)
          quantity <- ZIO.fromOption(order.quantity).orElseFail(respond.MethodNotAllowed)
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
      res <- httpServer.serveForever(storeResource.routes(handler))
    } yield res)
      .exitCode
      .provideSomeLayer[ZEnv with httpServer.HttpServer]((inventoryLayer ++ ordersLayer) >>> repository.Repository.inMemory)
      .provideSomeLayer[ZEnv](httpServer.HttpServer.live)
  }
}
