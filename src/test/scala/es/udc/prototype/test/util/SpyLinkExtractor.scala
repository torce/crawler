package es.udc.prototype.test.util

import es.udc.prototype.{Response, Extractor}
import scala.collection.mutable.{Set => MSet}
import scala.xml.XML

/**
 * User: david
 * Date: 5/03/14
 * Time: 21:35
 */

object SpyLinkExtractor {
  val visitedPaths: MSet[String] = MSet()
}

class SpyLinkExtractor extends Extractor {
  override def extractLinks(response: Response): Seq[String] = {
    SpyLinkExtractor.visitedPaths add response.task.url
    (XML.loadString(response.body) \\ "@href").toSeq.map(_.text)
  }

  override def extractInformation(response: Response): Unit = Unit
}
