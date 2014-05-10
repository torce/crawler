package es.udc.prototype.pipeline

import akka.actor.Actor.Receive
import scala.collection.mutable.{Map => MMap, Set => MSet, Queue => MQueue}
import com.typesafe.config.Config
import es.udc.prototype._
import spray.http.Uri.{Path, Authority}
import scala.collection.mutable
import es.udc.prototype.master.DefaultTask
import spray.http.{StatusCodes, StatusCode, Uri}
import akka.actor.ActorLogging
import es.udc.prototype.Response
import es.udc.prototype.Request
import scala.Some
import es.udc.prototype.master.DefaultTask

case class RobotsPathFiltered(userAgent: String) extends Exception

class RobotsFilter(config: Config) extends Stage with ActorLogging {
  val robotFiles = MMap[Authority, RobotsParser]()
  val allAllowed = MSet[Authority]()
  val waiting = MMap[Authority, MQueue[Request]]()

  def robotsRequest(url: Uri, headers: Map[String, String]): Request = {
    new Request(new DefaultTask(s"robots.txt-${url.authority}",
      url.withPath(new Path.Slash(new Path.Segment("robots.txt", Path.Empty))), 0), headers)
  }

  override def active: Receive = {
    case request@Request(task@Task(_, url, _), headers) =>
      if (allAllowed.contains(url.authority)) {
        right ! request
      } else if (waiting.contains(url.authority)) {
        waiting.get(url.authority).get.enqueue(request)
      } else {
        val userAgent = headers.getOrElse("User-Agent", "*")
        robotFiles.get(url.authority) match {
          case Some(robots) =>
            if (robots.allowed(userAgent, url)) {
              right ! request
            } else {
              left ! new Error(task, new RobotsPathFiltered(request.headers.getOrElse("User-Agent", "*")))
            }
          case None =>
            val queue = MQueue[Request](request)
            waiting.put(url.authority, queue)
            right ! robotsRequest(url, headers)
        }
      }

    case response@Response(task@Task(_, url, _), code, headers, body) =>
      if (url.path.toString().toLowerCase == "/robots.txt" &&
        (!robotFiles.contains(url.authority) || !allAllowed.contains(url.authority))) {
        if (code != StatusCodes.OK) {
          allAllowed += url.authority
          waiting.get(url.authority) match {
            case Some(queue) =>
              queue.foreach(right ! _)
              waiting.remove(url.authority) //All the request were sent, clear the entry
            case None => left ! response
          }
        } else {
          try {
            val parser = RobotsParser(body)
            robotFiles.put(url.authority, parser)
            waiting.get(url.authority) match {
              case Some(queue) =>
                val (allow, deny) = queue.partition(
                  r =>
                    parser.allowed(r.headers.getOrElse("User-Agent", "*"), r.task.url))

                allow.foreach(right ! _)
                deny.foreach(r => left ! new Error(task, new RobotsPathFiltered(r.headers.getOrElse("User-Agent", "*"))))

                waiting.remove(url.authority) //All the request were sent, clear the entry
              case None => left ! response
            }
          } catch {
            case e: Exception =>
              log.warning(s"Error parsing robots.txt of ${url.authority}: $e. All paths allowed")
              allAllowed += url.authority
          }
        }
      } else {
        left ! response
      }
    case error: Error =>
      left ! error
  }
}
