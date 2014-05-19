package es.udc.prototype

import akka.actor.{ActorLogging, Actor}
import spray.http._
import spray.client.pipelining._
import spray.http.HttpHeaders.RawHeader
import scala.language.implicitConversions
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * User: david
 * Date: 13/02/14
 * Time: 19:47
 */

case class SprayException(task: Task, exception: Exception) extends Exception

class Downloader extends Actor with ActorLogging {
  implicit def map2headers(headers: Map[String, String]): List[HttpHeader] =
    headers.view.toList.map {
      h => new RawHeader(h._1, h._2)
    }

  implicit def headers2map(headers: List[HttpHeader]): Map[String, String] =
    headers.foldLeft(Map[String, String]())((map, h) => map ++ Map(h.name -> h.value))

  implicit val executionContext = context.dispatcher

  def receive = {
    case Request(task@Task(id, url, _), headers) =>
      log.debug(s"Received Request of $id")
      val currentSender = sender
      val request = Get(url).withHeaders(headers)
      val pipeline = sendReceive
      val response: Future[Response] =
        for {
          httpResponse <- pipeline(request)
        } yield {
          log.debug(s"Http Response received of $id")
          new Response(task, httpResponse.status, httpResponse.headers, httpResponse.entity.asString)
        }
      response.onComplete {
        case Success(r) =>
          currentSender ! r
        case Failure(e: Throwable) =>
          currentSender ! new Error(task, e)
      }
  }
}
