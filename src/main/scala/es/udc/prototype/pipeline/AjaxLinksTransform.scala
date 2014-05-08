package es.udc.prototype.pipeline

import es.udc.prototype.{Request, Response}
import scala.xml._
import org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl
import scala.xml.transform.{RuleTransformer, RewriteRule}
import java.net.URLEncoder
import com.typesafe.config.Config
import spray.http.{IllegalUriException, Uri}
import es.udc.prototype.Response
import es.udc.prototype.Request
import scala.Some

class RewriteLinksRule(base: Uri) extends RewriteRule {
  override def transform(n: Node) = n match {
    case e@ <a>{_*}</a> =>
      val elem = e.asInstanceOf[Elem]
      elem.attributes.get("href") match {
        case Some(hrefs) if !hrefs.isEmpty =>
          elem % Attribute(null, "href", rewriteLink(hrefs(0).text), Null)
        case _ => e
      }
    case _ => n
  }

  def rewriteLink(href: String): String =
    if (href.startsWith("#!")) {
      try {
        Uri(s"/?_escaped_fragment_=${URLEncoder.encode(href.substring(2), "UTF-8")}").resolvedAgainst(base).toString()
      } catch {
        case _: IllegalUriException => href
      }
    } else {
      href
    }
}

class AjaxLinksTransform(config: Config) extends RequestFilter {
  lazy val parser = XML.withSAXParser(new SAXFactoryImpl().newSAXParser())

  override def handleRequest(request: Request): Option[Request] = Some(request)

  override def handleResponse(response: Response): Option[Response] = {
    Some(response.copy(body =
      new RuleTransformer(new RewriteLinksRule(response.task.url)).transform(
        parser.loadString(response.body)).toString()))
  }
}
