package es.udc.prototype.crawler

import akka.actor.{ActorLogging, Actor}
import spray.http.Uri
import es.udc.prototype.{Result, Response}
import java.io.IOException

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:25
 */

trait Crawler extends Actor with ActorLogging {

  def extractLinks(response: Response): Seq[Uri]

  def extractInformation(response: Response): Unit

  def receive = {
    case response: Response =>
      log.debug(s"Received Response of ${response.task.id}")
      extractInformation(response)
      log.debug(s"Generated Result of ${response.task.id}")

      // The crawler actor must be behind a router
      // The results are sent using the router ActorRef
      try {
        val r = new Result(response.task, extractLinks(response))
        sender.tell(r, context.parent)
      } catch {
        case e: IOException => log.error(s"Wat? $e")
      }

  }
}
