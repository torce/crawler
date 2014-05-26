package es.udc.scrawl.pipeline

import es.udc.scrawl.{Response, Request, Error}

/**
 * User: david
 * Date: 15/03/14
 * Time: 14:39
 */
trait RequestFilter extends Stage {
  def handleRequest(request: Request): Option[Request]

  def handleResponse(response: Response): Option[Response]

  def handleError(error: Error): Option[Error] = Some(error)

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
    case error: Error =>
      handleError(error) match {
        case Some(e) => left ! e
        case None =>
      }
  }
}
