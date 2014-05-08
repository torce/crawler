package es.udc.prototype

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.{Actor, Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import spray.routing.HttpService
import spray.can.Http
import akka.io.IO
import akka.io.Tcp.Bound
import spray.http.{StatusCodes, Uri}
import scala.concurrent.duration._
import es.udc.prototype.master.DefaultTask

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
    } ~ path("redirect") {
      redirect("/", StatusCodes.PermanentRedirect)
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

  def makeUrl(path: String) = Uri("http://" + host + ":" + port.toString + path)

  //Init the test server
  val server = system.actorOf(Props[TestServer])
  IO(Http) ! Http.Bind(server, host, port)
  expectMsgPF() {
    case Bound(_) => true
  }

  //Ignore the Bound message

  override def afterAll() {
    system.shutdown()
  }

  "A Downloader" should {
    "return a response from a request" in {
      val downloader = system.actorOf(Props[Downloader])
      val testUrl = makeUrl("/")
      downloader ! new Request(new DefaultTask("id", testUrl, 0), Map())
      expectMsgPF() {
        case Response(Task("id", _, 0), StatusCodes.OK, _, TestServer.root) => Unit
      }
    }
    "return the status code in the response" in {
      val downloader = system.actorOf(Props[Downloader])
      val testUrl = makeUrl("/unexistent")
      downloader ! new Request(new DefaultTask("id", testUrl, 0), Map())
      expectMsgPF() {
        case Response(Task("id", _, 0), StatusCodes.NotFound, _, _) => Unit
      }
    }
    "follow redirections" in {
      val downloader = system.actorOf(Props[Downloader])
      val testUrl = makeUrl("/redirect")
      downloader ! new Request(new DefaultTask("id", testUrl, 0), Map())
      expectMsgPF() {
        case Response(Task("id", _, 0), StatusCodes.OK, _, TestServer.root) => Unit
      }
    }
    "send an error message to Manager in case of any exception" in {
      val downloader = system.actorOf(Props[Downloader])
      val testUrl = Uri("http://0.0.0.0:1")
      downloader ! new Request(new DefaultTask("id", testUrl, 0), Map())
      expectMsgPF() {
        case Error(Task("id", _, 0), _) => Unit
      }
    }
  }
}
