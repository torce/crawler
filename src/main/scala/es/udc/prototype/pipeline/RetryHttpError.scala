package es.udc.prototype.pipeline

import es.udc.prototype.{Response, Request, Error}
import com.typesafe.config.Config
import scala.collection.mutable.{Map => MMap}
import spray.http.StatusCode
import scala.collection.JavaConversions._
import akka.actor.ActorLogging

case object HttpErrorMasxRetriesReached extends Exception

class RetryHttpError(config: Config) extends RequestFilter with ActorLogging {
  val requests = MMap[String, (Request, Int)]()

  val toFilter: Set[StatusCode] = config.getIntList("prototype.retry-http-error.errors").toSet[Integer]
    .map(i => StatusCode.int2StatusCode(i.intValue))
  val maxRetries: Int = config.getInt("prototype.retry-http-error.max-retries")

  override def handleRequest(request: Request) = {
    requests.put(request.task.id, (request, 0))
    Some(request)
  }

  override def handleResponse(response: Response) = {
    if (toFilter.contains(response.status)) {
      if (requests.contains(response.task.id)) {
        val (prevRequest, retries) = requests(response.task.id)
        if (retries < maxRetries) {
          log.info(s"Retrying request ${response.task.id} with code ${response.status}")
          requests.put(response.task.id, (prevRequest, retries + 1))
          right ! prevRequest
        } else {
          log.warning(s"Max requests reached for response ${response.task.id}")
          left ! new Error(response.task, HttpErrorMasxRetriesReached)
        }
      } else {
        // Unknown response, drop it
        log.warning(s"Dropping an unknown response with id ${response.task.id}")
      }
      None
    } else {
      // Response code correct, pass to the next stage and clear from Map if necessary
      if (toFilter.contains(response.status)) {
        requests.remove(response.task.id)
      }
      Some(response)
    }
  }
}
