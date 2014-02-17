package singleton

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import es.udc.prototype._

/**
 * User: david
 * Date: 14/02/14
 * Time: 16:18
 */

object SingletonMultiJvmMaster extends Startup {
  def main(args : Array[String]) {
    System.setProperty("akka.remote.netty.tcp.port", "2551")
    val system = ActorSystem("ClusterSystem", ConfigFactory.load("application.conf"))
    val master = initMaster(system)
    val crawler = initCrawler(system)
    val downloader = initDownloader(system)
    val manager =  initManager(system, master, downloader, crawler)
  }
}

object SingletonMultiJvmSeed extends Startup {
  def main(args : Array[String]) {
    System.setProperty("akka.remote.netty.tcp.port", "2552")
    val system = ActorSystem("ClusterSystem", ConfigFactory.load("application.conf"))
    val master = initMaster(system)
    val crawler = initCrawler(system)
    val downloader = initDownloader(system)
    val manager =  initManager(system, master, downloader, crawler)
  }
}