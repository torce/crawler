package es.udc.prototype

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.{Actor, Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import spray.testkit.ScalatestRouteTest
import spray.routing.{SimpleRoutingApp, HttpService}
import spray.can.Http
import akka.io.IO
import akka.io.Tcp.Bound

/**
 * User: david
 * Date: 18/02/14
 * Time: 20:46
 */
object TestServer {
  val root = "<html><body>Hello!</body></html>"
}

class TestServer extends Actor with HttpService {
  val route = {
    pathSingleSlash {
      get {
        complete(TestServer.root)
      }
    }
  }
  def actorRefFactory = context.system
  def receive = runRoute(route)
}

class DownloaderTest
  extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  val host = "localhost"
  val port = 5555
  def makeUrl(path : String) = "http://" + host + ":" + port.toString + path

  //Init the test server
  val server = system.actorOf(Props[TestServer])
  IO(Http) ! Http.Bind(server, host, port)
  ignoreMsg { case Bound(_) => true } //Ignore the Bound message

  override def afterAll() {
    system.shutdown()
  }

  "A Downloader" should {
    "return a response from a request" in {
      val downloader = system.actorOf(Props[Downloader])
      val testUrl = makeUrl("/")
      downloader ! new Request(testUrl,"id" , Map())
      expectMsgPF(){case Response(url, "id", _, TestServer.root) => Unit}
    }
  }
}
