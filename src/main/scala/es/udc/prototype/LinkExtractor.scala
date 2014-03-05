package es.udc.prototype

import scala.xml.XML

/**
 * User: david
 * Date: 5/03/14
 * Time: 18:56
 */
class LinkExtractor extends Extractor {
  override def extractLinks(response: Response): Seq[String] = {
    (XML.loadString(response.body) \\ "@href").toSeq.map(_.text)
  }

  override def extractInformation(response: Response) = Unit
}
