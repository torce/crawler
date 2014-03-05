package es.udc.prototype

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, Actor, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, BeforeAndAfterAll, WordSpecLike}
import spray.routing.HttpService
import akka.io.IO
import spray.can.Http
import akka.io.Tcp.Bound
import collection.mutable.{Set => MSet}
import scala.language.postfixOps
import scala.concurrent.duration._
import es.udc.prototype.test.util.SpyLinkExtractor

/**
 * User: david
 * Date: 14/02/14
 * Time: 14:57
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
}

class NodeTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("system.test.conf").withFallback(ConfigFactory.load())))
with ImplicitSender
with WordSpecLike
  with Matchers
with BeforeAndAfterAll
with Startup {

  //Init the test server
  val server = system.actorOf(Props[NodeTestServer])
  IO(Http) ! Http.Bind(server, NodeTestServer.host, NodeTestServer.port)
  ignoreMsg { case Bound(_) => true } //Ignore the Bound message

  override def afterAll() {
    system.shutdown()
  }

  "A Node" should {
    "retrieve task from initial URL and crawl it" in {
      import NodeTestServer.makeUrl
      val expected = Set(makeUrl("/"), makeUrl("/resource"), makeUrl("/stuff"))
      val listener : TestProbe = TestProbe()

      //Loaded also in the constructor, sbt does not execute test if there are constructor arguments
      val config = ConfigFactory.load("system.test.conf").withFallback(ConfigFactory.load())

      val master = initMaster(config, Some(listener.ref))
      listener.expectMsg(Started)

      val crawler = initCrawler(config)
      val downloader = initDownloader(config)
      initManager(config, master, downloader, crawler)

      master ! new NewTasks(Seq(makeUrl("/")))

      listener.expectMsgPF(150 seconds) {
        case Finished => SpyLinkExtractor.visitedPaths should be(expected)
      }
    }
  }
}