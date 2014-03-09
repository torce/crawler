package singleton

import akka.actor.Actor
import com.typesafe.config.ConfigFactory

/*import spray.routing.HttpService


/**
 * User: david
 * Date: 14/02/14
 * Time: 16:18
 */

object NodeTestServer {
  val host = "localhost"
  val port = 5555
  val root = "<html><body><a href=\""+makeUrl("/resource")+"\"></a><a href=\""+makeUrl("/stuff")+"\"></a> </body></html>"
  val resource = "<html><body><p>resource</p></body></html>"
  val stuff = "<html><body><p>stuff</p></body></html>"
  def makeUrl(path : String) : String = s"http://$host:$port$path"
}

class NodeTestServer extends Actor with HttpService {
  val route = {
    get {
      path("resource") {
        complete(NodeTestServer.resource)
      } ~
        path("stuff") {
          complete(NodeTestServer.stuff)
        } ~
        pathSingleSlash {
          complete(NodeTestServer.root)
        }
    }
  }

  def actorRefFactory = context.system
  def receive = runRoute(route)
}  */

object SingletonMultiJvmMaster {
  def main(args: Array[String]) {
    /*System.setProperty("akka.remote.netty.tcp.port", "2551")
    val system = ActorSystem("ClusterSystem", ConfigFactory.load())
    val master = initMaster(system)
    val crawler = initCrawler(system)
    val downloader = initDownloader(system)
    val manager =  initManager(system, master, downloader, crawler)  */
  }
}

object SingletonMultiJvmSeed {
  def main(args: Array[String]) {
    /*System.setProperty("akka.remote.netty.tcp.port", "2552")
    val system = ActorSystem("ClusterSystem", ConfigFactory.load())
    val master = initMaster(system)
    val crawler = initCrawler(system)
    val downloader = initDownloader(system)
    val manager =  initManager(system, master, downloader, crawler) */
  }
}