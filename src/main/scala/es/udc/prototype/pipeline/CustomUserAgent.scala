package es.udc.prototype.pipeline

import es.udc.prototype.{Response, Request}
import com.typesafe.config.Config

class CustomUserAgent(config: Config) extends RequestFilter {
  val userAgent = config.getString("prototype.custom-user-agent.user-agent")

  override def handleResponse(response: Response): Option[Response] = Some(response)

  override def handleRequest(request: Request): Option[Request] =
    Some(request.copy(headers = request.headers ++ Map("User-Agent" -> userAgent)))
}
