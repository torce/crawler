package es.udc.prototype

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, Actor, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, BeforeAndAfterAll, WordSpecLike}
import spray.routing.HttpService
import scala.xml.XML
import akka.io.IO
import spray.can.Http
import akka.io.Tcp.Bound
import collection.mutable.{Set => MSet}
import scala.language.postfixOps

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

class NodeTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  val visitedPaths : MSet[String] = MSet()

  //Init the test server
  val server = system.actorOf(Props[NodeTestServer])
  IO(Http) ! Http.Bind(server, NodeTestServer.host, NodeTestServer.port)
  ignoreMsg { case Bound(_) => true } //Ignore the Bound message

  override def afterAll() {
    system.shutdown()
  }

  class SimpleLinkExtractor extends Extractor {
    override def extractLinks(response : Response): Seq[String] = {
      visitedPaths add response.task.url
      (XML.loadString(response.body) \\ "@href").toSeq.map(_.text)
    }
    override def extractInformation(response : Response) = Unit
  }

  "A Node" should {
    "retrieve task from initial URL and crawl it" in {
      import NodeTestServer.makeUrl
      val expected = Set(makeUrl("/"), makeUrl("/resource"), makeUrl("/stuff"))
      val listener : TestProbe = TestProbe()

      val master = system.actorOf(Props(classOf[Master], listener.ref))
      val crawler = system.actorOf(Props(classOf[BaseCrawler], new SimpleLinkExtractor))
      val downloader = system.actorOf(Props[Downloader])
      system.actorOf(Props(classOf[Manager], master, downloader, crawler))
      master ! new NewTasks(Seq(makeUrl("/")))

      listener.expectMsgPF() {
        case Finished => visitedPaths should be(expected)
      }
    }
  }
}