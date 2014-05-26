package es.udc.scrawl.crawler

import scala.xml.XML
import org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl
import spray.http.{IllegalUriException, Uri}
import com.typesafe.config.Config
import es.udc.scrawl.Response

/**
 * User: david
 * Date: 5/03/14
 * Time: 18:56
 */
class LinkExtractor extends Crawler {
  def this(config: Config) = this() // Compatibility constructor

  lazy val parser = XML.withSAXParser(new SAXFactoryImpl().newSAXParser())

  override def extractLinks(response: Response) = {
    response.headers.get("Content-Type") match {
      case Some(contentType) if contentType.contains("text/html") =>
        val links = (parser.loadString(response.body) \\ "@href").map(_.text)
        links.flatMap {
          link: String =>
            try {
              if (!link.isEmpty) {
                val url = Uri(link).resolvedAgainst(response.task.url)
                if (url.effectivePort > 0) {
                  //If the port is defined for the scheme provided
                  Some(url)
                } else {
                  None
                }
              } else {
                None //Empty link
              }
            } catch {
              //Ignore bad URI
              case _: IllegalUriException => None
            }
        }
      case _ => Seq()
    }
  }

  override def extractInformation(response: Response) = Unit
}
