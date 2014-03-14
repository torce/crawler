package es.udc.prototype

import org.scalatest.{Matchers, WordSpecLike}
import spray.http.Uri

/**
 * User: david
 * Date: 13/03/14
 * Time: 19:38
 */
class LinkExtractorTest extends WordSpecLike with Matchers {

  def makeResponse(base: Uri, body: String) = new Response(new Task("", base), Map(), body)

  "A LinkExtractor" should {
    "extract the links of a given response in any order" in {
      val expected = Set(Uri("http://test1.test"), Uri("http://test2.test"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"http://test1.test\"/><a href=\"http://test2.test\"></a></body></html>")

      new LinkExtractor().extractLinks(response).toSet should be(expected)
    }

    "ignore malformed links" in {
      val expected = Set(Uri("http://test2.test"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\":/:/test1.test\"/><a href=\"http://test2.test\"></a></body></html>")

      new LinkExtractor().extractLinks(response).toSet should be(expected)
    }

    "ignore empty links" in {
      val expected = Set(Uri("http://test2.test"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"\"/><a href=\"http://test2.test\"></a></body></html>")

      new LinkExtractor().extractLinks(response).toSet should be(expected)
    }

    "transform relative URLs into absolute URLs" in {
      val expected = Seq(Uri("http://test.test/relative"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"./relative\"/></body></html>")

      new LinkExtractor().extractLinks(response) should be(expected)
    }

    "transform root relative URLs into absolute URLs" in {
      val expected = Seq(Uri("http://test.test/relative"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"/relative\"/></body></html>")

      new LinkExtractor().extractLinks(response) should be(expected)
    }

    "transform protocol relative URLs into absolute URLs" in {
      val expected = Seq(Uri("http://test.test/relative"))
      val response = makeResponse(Uri("http://test.test/"), "<html><body><a href=\"//test.test/relative\"/></body></html>")

      new LinkExtractor().extractLinks(response) should be(expected)
    }
  }
}
