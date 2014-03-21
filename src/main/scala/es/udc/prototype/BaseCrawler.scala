package es.udc.prototype

import akka.actor.{ActorLogging, Actor}
import spray.http.Uri

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:25
 */
trait Extractor {
  def extractLinks(response: Response): Seq[Uri]

  def extractInformation(response: Response): Unit
}

class BaseCrawler(extractor: Extractor) extends Actor with ActorLogging {


  def receive = {
    case response@Response(task, headers, body) =>
      log.info(s"Received Response of ${task.id}")
      extractor.extractInformation(response)
      log.info(s"Generated Result of ${task.id}")
      sender ! new Result(task, extractor.extractLinks(response))
  }
}
