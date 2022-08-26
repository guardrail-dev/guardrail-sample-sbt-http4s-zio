package example

import example.repository.{GetInventoryError, PlaceOrderError, RepositoryService, UnknownOrder}
import example.server.definitions.Order
import example.server.store.StoreHandler
import example.server.store.StoreResource.{DeleteOrderResponse, GetInventoryResponse, GetOrderByIdResponse, PlaceOrderResponse}
import zio.*

enum GetOrderByIdDownstreamErrors:
  case GOBIRepoError(error: UnknownOrder) extends GetOrderByIdDownstreamErrors

case class LiveStoreController(service: RepositoryService) extends StoreHandler[Task] {

  /**
   * getInventory
   *
   * Just grab from the repository
   *
   * Since we do not have multiple conflicting layers, we can just catchAll at the end to map errors
   */
  def getInventory(respond: GetInventoryResponse.type)(): Task[GetInventoryResponse] = {
    val action: ZIO[Any, GetInventoryError, GetInventoryResponse.Ok] = for {
      inventory <- service.getInventory
    } yield respond.Ok(inventory)

    action.catchAll {
      case GetInventoryError.StockroomUnavailable => ZIO.succeed(respond.InternalServerError("Stockroom unavailable, please try again later"))
    }
  }

  /**
   * placeOrder
   *
   * Grabbing optional fields from an optional body, so we mapError explicitly on every line to give a different example of error handling
   * repository.placeOrder also uses mapError, but translates from the repository error type into our error response.
   */
  def placeOrder(respond: PlaceOrderResponse.type)(body: Option[Order]): Task[PlaceOrderResponse] = {
    val action = for {
      order <- ZIO.fromOption(body).orElseFail(respond.MethodNotAllowed)
      id <- ZIO.fromOption(order.id).orElseFail(respond.MethodNotAllowed)
      petId <- ZIO.fromOption(order.petId).orElseFail(respond.MethodNotAllowed)
      quantity <- ZIO.fromOption(order.quantity).orElseFail(respond.MethodNotAllowed)
      res <- service.placeOrder(id, petId, quantity).mapError {
        case PlaceOrderError.InsufficientQuantity(id) => respond.MethodNotAllowed
      } // TODO: 405 isn't really applicable here
    } yield respond.Ok(res)

    action.merge // Second strategy of error handling, mapError to PlaceOrderResponses, then merge them all together
  }


  /**
   * getOrderById
   *
   * If we had a whole bunch of conflicting error types from various layers, it may be useful to define a bespoke
   * error tree to keep the function body terse, without losing any specificity or totality in catchAll
   */
  def getOrderById(respond: GetOrderByIdResponse.type)(orderId: Long): Task[GetOrderByIdResponse] = {
    val action = for order <- service.getOrder(orderId)
      .mapError(GetOrderByIdDownstreamErrors.GOBIRepoError.apply) yield respond.Ok(order)

    action.catchAll { // Third strategy of error handling, mapping to a custom ADT to accept disparate downstream error types (pseudo union types/coproduct)
      case GetOrderByIdDownstreamErrors.GOBIRepoError(repository.UnknownOrder(id)) => ZIO.succeed(respond.NotFound)
    }
  }


  /**
   * deleteOrder
   *
   * The underlying repository function call can fail with different errors, so mapError
   * those explicitly and use the .merge technique from placeOrder
   */
  def deleteOrder(respond: DeleteOrderResponse.type)(orderId: Long): Task[DeleteOrderResponse] = {
    val action = for {
      _ <- service.deleteOrder(orderId).mapError {
        case repository.UnknownOrder(id) => respond.NotFound
        case repository.AlreadyDeleted(id) => respond.BadRequest
      }
    } yield respond.Accepted

    action.merge
  }

}

object StoreController {
  val live: ZLayer[RepositoryService, Nothing, StoreHandler[Task]] = ZLayer {
    for service <- ZIO.service[RepositoryService] yield LiveStoreController(service)
  }
}
