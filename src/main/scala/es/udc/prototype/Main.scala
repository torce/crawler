package es.udc.prototype

import akka.actor._
import akka.actor.ActorDSL._
import com.typesafe.config.ConfigFactory
import org.rogach.scallop.{LazyScallopConf, ScallopOption}
import org.rogach.scallop.exceptions.{ScallopException, Help}

/**
 * User: david
 * Date: 12/02/14
 * Time: 20:35
 */

object Main {
  def main(args: Array[String]) : Unit = {
    object Conf extends LazyScallopConf(args.toList) {
      val port : ScallopOption[Int] = opt[Int](name = "port",
                                               descr = "Init port of the node",
                                               required = true,
                                               validate = p => p > 0 && p < 65535)
      val url: ScallopOption[String] = opt[String](name = "url",
        descr = "Initial url to crawl",
        required = true)
    }

    Conf.initialize {
      case Help(_) =>
        Conf.printHelp()
        return
      case e : ScallopException =>
        Conf.printHelp()
        return
    }

    System.setProperty("akka.remote.netty.tcp.port", Conf.port().toString)
    val config = ConfigFactory.load()
    implicit val system = ActorSystem("ClusterSystem", config)

    val listener = actor(new Act {
      become {
        case Started =>
          system.actorSelection("/user/manager/master-proxy") ! NewTasks(Seq(Conf.url.toString()))
        case Finished =>
          system.shutdown()
      }
    })
    system.actorOf(Props(classOf[Manager], config, listener), "manager")
  }
}
