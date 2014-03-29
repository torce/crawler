package es.udc.prototype.crawler

import akka.actor.{ActorLogging, Actor}
import spray.http.Uri
import es.udc.prototype.{Result, Response}

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:25
 */

trait Crawler extends Actor with ActorLogging {

  def extractLinks(response: Response): Seq[Uri]

  def extractInformation(response: Response): Unit

  def receive = {
    case response@Response(task, headers, body) =>
      log.info(s"Received Response of ${task.id}")
      extractInformation(response)
      log.info(s"Generated Result of ${task.id}")

      // The crawler actor must be behind a router
      // The results are sent using the router ActorRef
      sender.tell(new Result(task, extractLinks(response)), context.parent)
  }
}
