package es.udc.prototype.test.util

import es.udc.prototype.{LinkExtractor, Response}
import scala.collection.mutable.{Set => MSet}
import spray.http.Uri

/**
 * User: david
 * Date: 5/03/14
 * Time: 21:35
 */

object SpyLinkExtractor {
  val visitedPaths: MSet[Uri] = MSet()
}

class SpyLinkExtractor extends LinkExtractor {
  override def extractLinks(response: Response): Seq[Uri] = {
    SpyLinkExtractor.visitedPaths add response.task.url
    super.extractLinks(response)
  }
}
