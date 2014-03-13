package es.udc.prototype

import akka.actor.Actor
import akka.pattern.pipe
import spray.http._
import spray.client.pipelining._
import spray.http.HttpHeaders.RawHeader
import scala.language.implicitConversions
import scala.concurrent.Future

/**
 * User: david
 * Date: 13/02/14
 * Time: 19:47
 */
class Downloader extends Actor {
  implicit def map2headers(headers: Map[String, String]): List[HttpHeader] =
    headers.view.toList.map {
      h => new RawHeader(h._1, h._2)
    }

  implicit def headers2map(headers: List[HttpHeader]): Map[String, String] =
    headers.foldLeft(Map[String, String]())((map, h) => map ++ Map(h.name -> h.value))

  implicit val executionContext = context.dispatcher

  def receive = {
    case Request(task@Task(_, url), headers) =>
      val request = Get(url).withHeaders(headers)
      val pipeline = sendReceive
      val response: Future[Response] = for {
        httpResponse <- pipeline(request)
      } yield {
        new Response(task, httpResponse.headers, httpResponse.entity.asString)
      }
      response pipeTo sender
  }
}
