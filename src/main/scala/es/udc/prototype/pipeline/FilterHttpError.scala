package es.udc.prototype.pipeline

import es.udc.prototype.{Response, Request, Error}
import com.typesafe.config.Config
import scala.collection.JavaConversions._
import spray.http.StatusCode
import akka.actor.ActorLogging

case class FilteredHttpCode(statusCode: StatusCode) extends Exception

class FilterHttpError(config: Config) extends RequestFilter with ActorLogging {

  val toFilter: Set[StatusCode] = config.getIntList("prototype.filter-http-error.errors").toSet[Integer]
    .map(i => StatusCode.int2StatusCode(i.intValue))

  override def handleRequest(request: Request) = Some(request)

  override def handleResponse(response: Response) = {
    if (toFilter.contains(response.status)) {
      log.info(s"Filtering response ${response.task.id} with status ${response.status}")
      left ! new Error(response.task, new FilteredHttpCode(response.status))
      None
    } else {
      Some(response)
    }
  }
}
