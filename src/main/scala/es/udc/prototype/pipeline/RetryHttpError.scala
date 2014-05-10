package es.udc.prototype.pipeline

import es.udc.prototype.{Response, Request, Error}
import com.typesafe.config.Config
import scala.collection.mutable.{Map => MMap}
import spray.http.StatusCode
import scala.collection.JavaConversions._
import akka.actor.ActorLogging

class RetryHttpError(config: Config) extends Stage with ActorLogging {
  val requests = MMap[String, (Request, Int)]()

  val toFilter: Set[StatusCode] = config.getIntList("prototype.retry-http-error.errors").toSet[Integer]
    .map(i => StatusCode.int2StatusCode(i.intValue))
  val maxRetries: Int = config.getInt("prototype.retry-http-error.max-retries")

  override def active: Receive = {
    case request: Request =>
      requests.put(request.task.id, (request, 0))
      right ! request
    case response: Response =>
      if (toFilter.contains(response.status)) {
        if (requests.contains(response.task.id)) {
          val (prevRequest, retries) = requests(response.task.id)
          if (retries < maxRetries) {
            log.info(s"Retrying request ${response.task.id} with code ${response.status}")
            right ! prevRequest
          } else {
            log.warning(s"Max requests reached for response ${response.task.id}")
          }
        } else {
          // Unknown response, drop it
          log.warning(s"Dropping an unknown response with id ${response.task.id}")
        }
      } else {
        // Response code correct, pass to the next stage and clear from Map if necessary
        if (toFilter.contains(response.status)) {
          requests.remove(response.task.id)
        }
        left ! response
      }
    case error: Error =>
      left ! error
  }
}
