package es.udc.scrawl.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import es.udc.scrawl.master.DefaultTask
import es.udc.scrawl.Response
import spray.http.{StatusCodes, Uri}
import scala.concurrent.duration._

class AjaxLinksTransformTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  val config = ConfigFactory.load("application.test.conf")

  def initAjax() = {
    val ajax = system.actorOf(Props(classOf[AjaxLinksTransform], config))
    val listener = TestProbe()
    listener.send(ajax, new LeftRight(listener.ref, listener.ref))
    listener.expectMsg(Initialized)
    (ajax, listener)
  }

  "An AjaxLinksTransformer" should {
    "transform the url fragments into a escaped version" in {
      val (ajax, listener) = initAjax()
      val body = "<html><body><a href=\"#!key=value\"/></body></html>"

      listener.send(ajax, new Response(new DefaultTask("id", Uri("http://www.example.com/"), 0),
        StatusCodes.OK, Map("Content-Type" -> "text/html"), body))

      //Due to a bug, the TagSoup parser inserts an attribute shape="rect" into the a tags
      listener.expectMsgPF() {
        case Response(_, _, headers,
        """<html><body><a href="http://www.example.com/?_escaped_fragment_=key%3Dvalue" shape="rect"/></body></html>""")
          if headers("Content-Type") == "text/html" => Unit
      }
    }

  }
}
