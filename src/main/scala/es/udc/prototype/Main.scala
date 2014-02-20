package es.udc.prototype

import akka.actor.{ActorRef, PoisonPill, Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import akka.contrib.pattern.ClusterSingletonManager
import es.udc.prototype.util.SingletonProxy
import org.rogach.scallop.{LazyScallopConf, ScallopOption}
import org.rogach.scallop.exceptions._

/**
 * User: david
 * Date: 12/02/14
 * Time: 20:35
 */

trait Startup {
  def initMaster(system : ActorSystem) : ActorRef = {
    system.actorOf(ClusterSingletonManager.props(
      singletonProps = handOverData => Props[Master],
      singletonName = "master",
      terminationMessage = PoisonPill,
      role = None),
      name = "masterManager")
    system.actorOf(Props(classOf[SingletonProxy], "masterManager", "master"))
  }

  def initCrawler(system : ActorSystem) : ActorRef = {
    system.actorOf(Props(classOf[BaseCrawler], new Extractor {
      def extractLinks(response: Response): Seq[String] = Seq()
      def extractInformation(response: Response): Unit = Unit
    }), "baseCrawler")
  }

  def initDownloader(system : ActorSystem) : ActorRef = {
    system.actorOf(Props[Downloader], "downloader")
  }

  def initManager(system : ActorSystem, master : ActorRef, downloader : ActorRef, crawler : ActorRef) : ActorRef = {
    system.actorOf(Props(classOf[Manager], master, downloader, crawler), "manager")
  }
}


object Main extends Startup {
  def main(args: Array[String]) : Unit = {
    object Conf extends LazyScallopConf(args.toList) {
      val port : ScallopOption[Int] = opt[Int](name = "port",
                                               descr = "Init port of the node",
                                               required = true,
                                               validate = p => p > 0 && p < 65535)
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

    val system = ActorSystem("ClusterSystem", ConfigFactory.load("application.conf"))
    val master = initMaster(system)
    val crawler = initCrawler(system)
    val downloader = initDownloader(system)
    val manager =  initManager(system, master, downloader, crawler)
  }
}
