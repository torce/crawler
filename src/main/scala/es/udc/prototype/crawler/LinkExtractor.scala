package es.udc.prototype.crawler

import scala.xml.XML
import org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl
import spray.http.{IllegalUriException, Uri}
import com.typesafe.config.Config
import es.udc.prototype.Response

/**
 * User: david
 * Date: 5/03/14
 * Time: 18:56
 */
class LinkExtractor extends Crawler {
  def this(config: Config) = this() // Compatibility constructor

  lazy val parser = XML.withSAXParser(new SAXFactoryImpl().newSAXParser())

  override def extractLinks(response: Response) = {
    val links = (parser.loadString(response.body) \\ "@href").map(_.text)
    links.flatMap {
      link: String =>
        try {
          if (!link.isEmpty)
            Some(Uri(link).resolvedAgainst(response.task.url))
          else
            None
        } catch {
          //Ignore bad URI
          case _: IllegalUriException => None
        }
    }
  }

  override def extractInformation(response: Response) = {
    log.info(s"${response.task.url}")
  }
}
