package es.udc.scrawl.crawler

import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest._
import es.udc.scrawl.{Result, Response}
import spray.http.StatusCode.int2StatusCode
import es.udc.scrawl.master.DefaultTask

/**
 * User: david
 * Date: 13/02/14
 * Time: 20:02
 */

class MockExtractor extends Crawler {
  var informationExtracted = 0

  def extractLinks(response: Response) = Seq("1", "2")

  def extractInformation(response: Response) {
    informationExtracted += 1
  }
}

class CrawlerTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  "A Crawler" should {
    "extract information and return a Result with the extracted links" in {
      val testResponse = new Response(new DefaultTask("id", "url", 0), int2StatusCode(200), Map(), "body")

      val crawler = TestActorRef(new MockExtractor)
      crawler ! testResponse
      expectMsg(new Result(new DefaultTask("id", "url", 0), Seq("1", "2")))
      crawler.underlyingActor.informationExtracted should be(1)
    }
  }
}
