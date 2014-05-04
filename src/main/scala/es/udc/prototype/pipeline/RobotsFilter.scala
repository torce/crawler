package es.udc.prototype.pipeline

import akka.actor.Actor.Receive
import scala.collection.mutable.{Map => MMap, Set => MSet, Queue => MQueue}
import com.typesafe.config.Config
import es.udc.prototype.{Request, Response, Task}
import spray.http.Uri.{Path, Authority}
import scala.collection.mutable
import es.udc.prototype.master.DefaultTask
import spray.http.{StatusCodes, StatusCode, Uri}
import es.udc.prototype.Request
import akka.actor.ActorLogging

class RobotsFilter(config: Config) extends Stage with ActorLogging {
  val robotFiles = MMap[Authority, RobotsParser]()
  val allAllowed = MSet[Authority]()
  val waiting = MMap[Authority, MQueue[Request]]()

  def robotsRequest(url: Uri, headers: Map[String, String]): Request = {
    new Request(new DefaultTask(s"robots.txt-${url.authority}",
      url.withPath(new Path.Slash(new Path.Segment("robots.txt", Path.Empty))), 0), headers)
  }

  def userAgentOrDefault(headers: Map[String, String]) = {

  }

  override def active: Receive = {
    case request@Request(Task(_, url, _), headers) =>
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
              log.info(s"Filtering task ${request.task.id} with url $url due to robots.txt")
            }
          case None =>
            val queue = MQueue[Request](request)
            waiting.put(url.authority, queue)
            right ! robotsRequest(url, headers)
        }
      }

    case response@Response(Task(_, url, _), code, headers, body) =>
      if (url.path.toString().toLowerCase == "/robots.txt" &&
        (!robotFiles.contains(url.authority) || !allAllowed.contains(url.authority))) {
        if (code != StatusCodes.OK) {
          allAllowed += url.authority
          waiting.get(url.authority) match {
            case Some(queue) => queue.foreach(right ! _)
            case None => left ! response
          }
        } else {
          val parser = RobotsParser(body)
          robotFiles.put(url.authority, parser)
          waiting.get(url.authority) match {
            case Some(queue) => queue.withFilter(r =>
              parser.allowed(r.headers.getOrElse("User-Agent", "*"), r.task.url)).foreach(right ! _)
              waiting.remove(url.authority) //All the request were sent, clear the entry
            case None => left ! response
          }
        }
      } else {
        left ! response
      }
  }
}
