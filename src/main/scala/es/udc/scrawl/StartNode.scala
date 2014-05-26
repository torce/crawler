package es.udc.scrawl

import akka.actor._
import akka.actor.ActorDSL._
import com.typesafe.config.ConfigFactory
import org.rogach.scallop.{LazyScallopConf, ScallopOption}
import org.rogach.scallop.exceptions.{ScallopException, Help}
import spray.http.Uri

/**
 * User: david
 * Date: 12/02/14
 * Time: 20:35
 */
// $COVERAGE-OFF$
trait StartNode {
  def start(port: Int, url: Option[Uri]): ActorRef = {
    System.setProperty("akka.remote.netty.tcp.port", port.toString)
    val config = ConfigFactory.load()
    implicit val system = ActorSystem("ClusterSystem", config)

    val listener = actor(new Act {
      become {
        case Started =>
          url match {
            case Some(u) =>
              system.actorSelection("/user/manager/master-proxy") ! new NewTasks(Seq(u))
            case None =>
          }
        case Finished =>
          system.shutdown()
      }
    })
    system.actorOf(Props(classOf[Manager], config, listener), "manager")
  }
}

object NodeApp extends StartNode with App {
  object Conf extends LazyScallopConf(args.toList) {
    val port : ScallopOption[Int] = opt[Int](name = "port",
      descr = "Init port of the node",
      required = true,
      validate = p => p > 0 && p < 65535)
    val url: ScallopOption[String] = opt[String](name = "url",
      descr = "Initial url to crawl",
      required = false)
  }

  Conf.initialize {
    case Help(_) =>
      Conf.printHelp()
      System.exit(0)
    case e : ScallopException =>
      Conf.printHelp()
      System.exit(0)
  }

  start(Conf.port(), Conf.url.map(Uri(_)).get )
}
// $COVERAGE-ON$