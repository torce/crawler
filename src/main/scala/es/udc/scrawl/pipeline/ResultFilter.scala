package es.udc.scrawl.pipeline

import es.udc.scrawl.{Result, Response, Error}

/**
 * User: david
 * Date: 15/03/14
 * Time: 15:14
 */
trait ResultFilter extends Stage {
  def handleResponse(response: Response): Option[Response]

  def handleResult(result: Result): Option[Result]

  def handleError(error: Error): Option[Error] = Some(error)

  override def active = {
    case response: Response =>
      handleResponse(response) match {
        case Some(r) => right ! r
        case None =>
      }
    case result: Result =>
      handleResult(result) match {
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
