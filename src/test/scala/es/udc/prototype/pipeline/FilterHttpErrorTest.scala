package es.udc.prototype.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.collection.JavaConversions._
import es.udc.prototype.{Request, Task, Response}
import spray.http.{StatusCode, Uri}

class FilterHttpErrorTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  val config = ConfigFactory.load("application.test.conf")
  val errors = config.getIntList("prototype.filter-http-error.errors").toList
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
      errors.foreach {
        e =>
          listener.send(httpFilter, new Response(new Task("id", Uri.Empty, 0), StatusCode.int2StatusCode(e), Map(), ""))
          listener.expectNoMsg()
      }
    }
    "allow the rest of the error codes" in {
      val (httpFilter, listener) = initFilterHttp()
      allowed.foreach {
        e =>
          val response = new Response(new Task("id", Uri.Empty, 0), StatusCode.int2StatusCode(e), Map(), "")
          listener.send(httpFilter, new Response(new Task("id", Uri.Empty, 0), StatusCode.int2StatusCode(e), Map(), ""))
          listener.expectMsg(response)
      }
    }
    "do not modify the Requests" in {
      val (httpFilter, listener) = initFilterHttp()
      val request = new Request(new Task("id", Uri.Empty, 0), Map())
      listener.send(httpFilter, request)
      listener.expectMsg(request)
    }
  }
}
