package es.udc.prototype

import akka.actor.Actor
import akka.pattern.pipe
import scala.concurrent.Future
import spray.http.Uri

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:25
 */
trait Extractor {
  def extractLinks(response: Response): Seq[Uri]

  def extractInformation(response : Response) : Unit
}

class BaseCrawler(extractor : Extractor) extends Actor {
  import context.dispatcher
  def receive = {
    case response@Response(task, headers, body) =>
      val currentSender = sender
      Future {
        extractor.extractInformation(response)
        new Result(task, extractor.extractLinks(response))
      } pipeTo currentSender
  }
}
