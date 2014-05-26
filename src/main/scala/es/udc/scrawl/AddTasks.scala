package es.udc.scrawl

import org.rogach.scallop.{ScallopOption, LazyScallopConf}
import org.rogach.scallop.exceptions.{ScallopException, Help}
import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterClient
import spray.http.Uri
import com.typesafe.config.{ConfigFactory, Config}

// $COVERAGE-OFF$
object AddTasks {
  def main(args: Array[String]) : Unit = {
    object Conf extends LazyScallopConf(args.toList) {
      val port : ScallopOption[Int] = opt[Int](
        name = "port",
        descr = "The port of the node",
        required = true,
        validate = p => p > 0 && p < 65535)
      val host : ScallopOption[String] = opt[String](
        name = "host",
        descr = "The host of the node",
        required = true)
      val sysname : ScallopOption[String] = opt[String](
        name = "sysname",
        descr = "The name of the actor system in the cluster",
        required = true)
      val url : ScallopOption[String] = opt[String](
        name = "url",
        descr = "The url to crawl",
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
    import Conf._
    val system = ActorSystem("add", ConfigFactory.parseString(""))
    val address = s"akka.tcp://${sysname()}@${host()}:${port()}/user/receptionist"
    val initialContacts = Set(system.actorSelection(address))
    val client = system.actorOf(ClusterClient.props(initialContacts))
    client ! ClusterClient.SendToAll("/user/manager/master-proxy", new NewTasks(Seq(Uri(url()))))

    system.shutdown()
  }
}
// $COVERAGE-ON$