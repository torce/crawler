package es.udc.prototype

import akka.actor._
import akka.actor.ActorDSL._
import com.typesafe.config.{Config, ConfigFactory}
import akka.contrib.pattern.ClusterSingletonManager
import es.udc.prototype.util.SingletonProxy
import org.rogach.scallop.{LazyScallopConf, ScallopOption}
import org.rogach.scallop.exceptions.{ScallopException, Help}

/**
 * User: david
 * Date: 12/02/14
 * Time: 20:35
 */

trait Startup {
  def initMaster(config: Config, listener: Option[ActorRef] = None)(implicit system: ActorSystem): ActorRef = {
    val listenerRef = listener.getOrElse(actor(new Act {
      become {
        case Finished =>
          println("Crawler finished")
          system.shutdown()
      }
    }))

    val masterProps = Props(
      Class.forName(config.getString("prototype.master.class")), config, listenerRef
    )

    system.actorOf(ClusterSingletonManager.props(
      singletonProps = _ => masterProps,
      singletonName = "master",
      terminationMessage = PoisonPill,
      role = None),
      name = "master-manager")
    system.actorOf(Props(classOf[SingletonProxy], "master-manager", "master"))
  }

  def initCrawler(config: Config)(implicit system: ActorSystem): ActorRef = {
    val extractor = Class.forName(config.getString("prototype.crawler.extractor.class")).newInstance()
    val crawlerProps = Props(
      Class.forName(config.getString("prototype.crawler.class")), extractor
    )
    system.actorOf(crawlerProps, "baseCrawler")
  }

  def initDownloader(config: Config)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(Props(Class.forName(config.getString("prototype.downloader.class"))), "downloader")
  }

  def initManager(config: Config, master: ActorRef, downloader: ActorRef, crawler: ActorRef)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(
      Props(Class.forName(config.getString("prototype.manager.class")),
        config, master, downloader, crawler), "manager")
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
    val config = ConfigFactory.load()
    implicit val system = ActorSystem("ClusterSystem", config)

    val master = initMaster(config)
    val crawler = initCrawler(config)
    val downloader = initDownloader(config)
    val manager = initManager(config, master, downloader, crawler)
  }
}
