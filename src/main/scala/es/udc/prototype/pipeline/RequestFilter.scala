package es.udc.prototype.pipeline

import es.udc.prototype.{Response, Request}

/**
 * User: david
 * Date: 15/03/14
 * Time: 14:39
 */
trait RequestFilter extends Stage {
  def handleRequest(request: Request): Request

  def handleResponse(response: Response): Response

  override def active = {
    case request: Request =>
      right ! handleRequest(request)
    case response: Response =>
      left ! handleResponse(response)
  }
}
