package es.udc.scrawl.pipeline

import scala.collection.mutable.{Map => MMap, Set => MSet, Queue => MQueue}
import com.typesafe.config.Config
import es.udc.scrawl._
import spray.http.Uri.{Path, Authority}
import spray.http.{StatusCodes, StatusCode, Uri}
import akka.actor.{ReceiveTimeout, ActorLogging}
import es.udc.scrawl.Response
import es.udc.scrawl.Request
import scala.Some
import es.udc.scrawl.master.DefaultTask
import es.udc.scrawl.Error
import scala.concurrent.duration._

case class RobotsPathFiltered(userAgent: String) extends Exception

class RobotsFilter(config: Config) extends Stage with ActorLogging {

  import context.dispatcher

  val robotFiles = MMap[Authority, RobotsParser]()
  val allAllowed = MSet[Authority]()
  val waiting = MMap[Authority, (Long, Request, MSet[Request])]()
  val retryTimeout = config.getInt("scrawl.robots-filter.retry-timeout")

  def robotsRequest(url: Uri, headers: Map[String, String]): Request = {
    new Request(new DefaultTask(s"robots.txt-${url.authority}",
      url.withPath(new Path.Slash(new Path.Segment("robots.txt", Path.Empty))), 0), headers)
  }

  case object CheckTimeouts

  override def preStart() {
    context.system.scheduler.scheduleOnce(retryTimeout.milliseconds, self, CheckTimeouts)
  }

  //Override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  override def active: Receive = {
    case request@Request(task@Task(_, url, _), headers) =>
      if (allAllowed.contains(url.authority)) {
        right ! request
      } else if (waiting.contains(url.authority)) {
        waiting.get(url.authority).get._3 += request
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
            val set = MSet[Request](request)
            val robots = robotsRequest(url, headers)
            waiting.put(url.authority, (System.currentTimeMillis(), robots, set))
            right ! robots
        }
      }

    case response@Response(task@Task(_, url, _), code, headers, body) =>
      if (url.path.toString().toLowerCase == "/robots.txt" &&
        (!robotFiles.contains(url.authority) || !allAllowed.contains(url.authority))) {
        if (code != StatusCodes.OK) {
          allAllowed += url.authority
          waiting.get(url.authority) match {
            case Some((_, _, set)) =>
              set.foreach(right ! _)
              waiting.remove(url.authority) //All the request were sent, clear the entry
            case None => left ! response //No waiting request, the robots request comes from another stage
          }
        } else {
          try {
            val parser = RobotsParser(body)
            robotFiles.put(url.authority, parser)
            waiting.get(url.authority) match {
              case Some((_, _, set)) =>
                val (allow, deny) = set.partition(
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
    case error@Error(Task(_, url, _), _) =>
      if (url.path.toString().toLowerCase == "/robots.txt" &&
        (!robotFiles.contains(url.authority) || !allAllowed.contains(url.authority))) {
        allAllowed += url.authority
        waiting.get(url.authority) match {
          case Some((_, _, set)) =>
            set.foreach(right ! _)
            waiting.remove(url.authority) //All the request were sent, clear the entry
          case None => left ! error //No waiting request, the robots request comes from another stage
        }
      } else {
        left ! error
      }
    case CheckTimeouts =>
      waiting.foreach {
        case (_, (start: Long, robots: Request, _)) =>
          if (System.currentTimeMillis() - retryTimeout > start) {
            right ! robots
          }
      }
      context.system.scheduler.scheduleOnce(retryTimeout.milliseconds, self, CheckTimeouts)
  }
}
