package es.udc.prototype

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest._

/**
 * User: david
 * Date: 13/02/14
 * Time: 20:02
 */
class BaseCrawlerTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(ActorSystem("TestSystem"))
  }

  "A base Crawler" should {
    "extract information and return a Result with the extracted links" in {
      var informationExtracted : Int = 0
      val testResponse = new Response("url", "id", Map(), "body")

      class MockExtractor extends Extractor {
        def extractLinks(response : Response) = Seq("1", "2")
        def extractInformation(response : Response) {
          informationExtracted = informationExtracted + 1
        }
      }

      val baseCrawler = system.actorOf(Props(classOf[BaseCrawler], new MockExtractor))
      baseCrawler ! testResponse
      expectMsg(new Result("id", Seq("1", "2")))
      informationExtracted should be(1)
    }
  }
}
