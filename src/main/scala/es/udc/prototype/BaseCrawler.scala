package es.udc.prototype

import akka.actor.Actor
import akka.pattern.pipe
import scala.concurrent.Future

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:25
 */
trait Extractor {
  def extractLinks(response : Response) : Seq[String]
  def extractInformation(response : Response) : Unit
}

class BaseCrawler(extractor : Extractor) extends Actor {
  import context.dispatcher
  def receive = {
    case response @ Response(url, id, headers, body) =>
      val currentSender = sender
      Future {
        extractor.extractInformation(response)
        new Result(id, extractor.extractLinks(response))
      } pipeTo currentSender
  }
}
