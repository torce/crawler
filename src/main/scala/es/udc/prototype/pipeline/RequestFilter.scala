package es.udc.prototype.pipeline

import scala.concurrent.Future
import akka.pattern.pipe
import es.udc.prototype.{Response, Request}

/**
 * User: david
 * Date: 15/03/14
 * Time: 14:39
 */
trait RequestFilter extends Stage {

  import context.dispatcher

  def handleRequest(request: Request): Request

  def handleResponse(response: Response): Response

  override def active = {
    case request: Request =>
      Future {
        handleRequest(request)
      } pipeTo right
    case response: Response =>
      Future {
        handleResponse(response)
      } pipeTo left
  }
}
