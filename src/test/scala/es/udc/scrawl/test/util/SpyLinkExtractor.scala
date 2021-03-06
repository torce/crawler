package es.udc.scrawl.test.util

import es.udc.scrawl.Response
import scala.collection.mutable.{Set => MSet}
import spray.http.Uri
import com.typesafe.config.Config
import es.udc.scrawl.crawler.LinkExtractor

/**
 * User: david
 * Date: 5/03/14
 * Time: 21:35
 */

object SpyLinkExtractor {
  val visitedPaths: MSet[Uri] = MSet()
}

class SpyLinkExtractor(config: Config) extends LinkExtractor(config) {
  override def extractLinks(response: Response): Seq[Uri] = {
    SpyLinkExtractor.visitedPaths add response.task.url
    super.extractLinks(response)
  }
}
