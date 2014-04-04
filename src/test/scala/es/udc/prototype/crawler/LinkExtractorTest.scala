package es.udc.prototype.crawler

import org.scalatest.{Matchers, WordSpecLike}
import spray.http.Uri
import akka.testkit.{TestKit, TestActorRef}
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import es.udc.prototype.{Task, Response}

/**
 * User: david
 * Date: 13/03/14
 * Time: 19:38
 */
class LinkExtractorTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
with WordSpecLike with Matchers {

  def makeResponse(base: Uri, body: String) = new Response(new Task("", base, 0), 200, Map(), body)

  def initExtractor = TestActorRef(new LinkExtractor).underlyingActor

  "A LinkExtractor" should {
    "extract the links of a given response in any order" in {
      val expected = Set(Uri("http://test1.test"), Uri("http://test2.test"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"http://test1.test\"/><a href=\"http://test2.test\"></a></body></html>")

      initExtractor.extractLinks(response).toSet should be(expected)
    }

    "ignore malformed links" in {
      val expected = Set(Uri("http://test2.test"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\":/:/test1.test\"/><a href=\"http://test2.test\"></a></body></html>")

      initExtractor.extractLinks(response).toSet should be(expected)
    }

    "ignore empty links" in {
      val expected = Set(Uri("http://test2.test"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"\"/><a href=\"http://test2.test\"></a></body></html>")

      initExtractor.extractLinks(response).toSet should be(expected)
    }

    "transform relative URLs into absolute URLs" in {
      val expected = Seq(Uri("http://test.test/relative"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"./relative\"/></body></html>")

      initExtractor.extractLinks(response) should be(expected)
    }

    "transform root relative URLs into absolute URLs" in {
      val expected = Seq(Uri("http://test.test/relative"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"/relative\"/></body></html>")

      initExtractor.extractLinks(response) should be(expected)
    }

    "transform protocol relative URLs into absolute URLs" in {
      val expected = Seq(Uri("http://test.test/relative"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"//test.test/relative\"/></body></html>")

      initExtractor.extractLinks(response) should be(expected)
    }
  }
}
