package es.udc.prototype

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor._
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, BeforeAndAfterAll, WordSpecLike}
import spray.routing.HttpService
import akka.io.IO
import spray.can.Http
import scala.language.postfixOps
import es.udc.prototype.test.util.SpyLinkExtractor
import akka.io.Tcp.Bound
import spray.http.Uri

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

  def makeUrl(path: String): Uri = Uri(s"http://$host:$port$path")
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
with BeforeAndAfterAll {

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

      //Loaded also in the constructor, sbt does not execute test if there are constructor arguments
      val config = ConfigFactory.load("system.test.conf").withFallback(ConfigFactory.load())

      system.actorOf(Props(classOf[Manager], config, self), "manager")

      expectMsg(Started)
      system.actorSelection("/user/manager/master-proxy") ! new NewTasks(Seq(makeUrl("/")))


      expectMsgPF() {
        case Finished => SpyLinkExtractor.visitedPaths should be(expected)
      }
    }
  }
}