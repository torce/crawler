package es.udc.prototype.pipeline

import es.udc.prototype.{Result, Response}
import scala.concurrent.Future
import akka.pattern.pipe

/**
 * User: david
 * Date: 15/03/14
 * Time: 15:14
 */
trait ResultFilter extends Stage {

  import context.dispatcher

  def handleResponse(response: Response): Response

  def handleResult(result: Result): Result

  override def active = {
    case response: Response =>
      Future {
        handleResponse(response)
      } pipeTo right
    case result: Result =>
      Future {
        handleResult(result)
      } pipeTo left
  }

}
