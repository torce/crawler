package es.udc.prototype.pipeline

import es.udc.prototype.{Result, Response}

/**
 * User: david
 * Date: 15/03/14
 * Time: 15:14
 */
trait ResultFilter extends Stage {
  def handleResponse(response: Response): Response

  def handleResult(result: Result): Result

  override def active = {
    case response: Response =>
      right ! handleResponse(response)
    case result: Result =>
      left ! handleResult(result)
  }

}
