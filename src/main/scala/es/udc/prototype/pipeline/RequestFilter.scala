package es.udc.prototype.pipeline

import es.udc.prototype.{Response, Request}

/**
 * User: david
 * Date: 15/03/14
 * Time: 14:39
 */
trait RequestFilter extends Stage {
  def handleRequest(request: Request): Option[Request]

  def handleResponse(response: Response): Option[Response]

  override def active = {
    case request: Request =>
      handleRequest(request) match {
        case Some(r) => right ! r
        case None =>
      }
    case response: Response =>
      handleResponse(response) match {
        case Some(r) => left ! r
        case None =>
      }
  }
}
