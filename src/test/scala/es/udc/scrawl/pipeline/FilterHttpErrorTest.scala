package es.udc.scrawl.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.collection.JavaConversions._
import es.udc.scrawl.{Request, Response, Error}
import spray.http.{StatusCode, Uri}
import es.udc.scrawl.master.DefaultTask

class FilterHttpErrorTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  val config = ConfigFactory.load("application.test.conf")
  val errors = config.getIntList("scrawl.filter-http-error.errors").toList
  val allowed = Seq(404, 300, 302)

  def initFilterHttp() = {
    val maxDepth = system.actorOf(Props(classOf[FilterHttpError], config))
    val listener = TestProbe()
    listener.send(maxDepth, new LeftRight(listener.ref, listener.ref))
    listener.expectMsg(Initialized)
    (maxDepth, listener)
  }

  "A FilterHttpError stage" should {
    "filter the configured http errors" in {
      val (httpFilter, listener) = initFilterHttp()
      val task = new DefaultTask("id", Uri.Empty, 0)
      errors.foreach {
        e =>
          listener.send(httpFilter, new Response(task, StatusCode.int2StatusCode(e), Map(), ""))
          listener.expectMsg(new Error(task, new FilteredHttpCode(StatusCode.int2StatusCode(e))))
      }
    }
    "allow the rest of the error codes" in {
      val (httpFilter, listener) = initFilterHttp()
      allowed.foreach {
        e =>
          val response = new Response(new DefaultTask("id", Uri.Empty, 0), StatusCode.int2StatusCode(e), Map(), "")
          listener.send(httpFilter, new Response(new DefaultTask("id", Uri.Empty, 0), StatusCode.int2StatusCode(e), Map(), ""))
          listener.expectMsg(response)
      }
    }
    "do not modify the Requests" in {
      val (httpFilter, listener) = initFilterHttp()
      val request = new Request(new DefaultTask("id", Uri.Empty, 0), Map())
      listener.send(httpFilter, request)
      listener.expectMsg(request)
    }
  }
}
