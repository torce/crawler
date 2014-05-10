package es.udc.prototype.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import es.udc.prototype.{Error, Response, Request}
import spray.http.Uri.Empty
import spray.http.StatusCodes
import es.udc.prototype.master.DefaultTask

/**
 * User: david
 * Date: 15/03/14
 * Time: 14:52
 */

class MockRequestFilter extends RequestFilter {
  override def handleRequest(r: Request) = {
    Some(new Request(new DefaultTask("handled", r.task.url, 0), r.headers))
  }

  override def handleResponse(r: Response) = {
    Some(new Response(new DefaultTask("handled", r.task.url, 0), StatusCodes.OK, r.headers, r.body))
  }
}

class RequestFilterTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  def initFilter() = {
    val filter = system.actorOf(Props[MockRequestFilter])
    val left = TestProbe()
    val right = TestProbe()

    filter ! new LeftRight(left.ref, right.ref)
    expectMsg(Initialized)

    (filter, left, right)
  }

  "A RequestFilter" should {
    "apply filter to request and send it to right" in {
      val (filter, _, right) = initFilter()
      val input = new Request(new DefaultTask("unhandled", Empty, 0), Map())
      val expected = new Request(new DefaultTask("handled", Empty, 0), Map())

      filter ! input
      right.expectMsg(expected)
    }
    "apply filter to response and send it to left" in {
      val (filter, left, _) = initFilter()
      val input = new Response(new DefaultTask("unhandled", Empty, 0), StatusCodes.OK, Map(), "body")
      val expected = new Response(new DefaultTask("handled", Empty, 0), StatusCodes.OK, Map(), "body")

      filter ! input
      left.expectMsg(expected)
    }
    "send all the error messages to the left" in {
      val (filter, left, _) = initFilter()
      val error = new Error(new DefaultTask("unhandled", Empty, 0), new Exception)

      filter ! error
      left.expectMsg(error)
    }
  }
}
