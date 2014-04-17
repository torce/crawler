package es.udc.prototype.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import es.udc.prototype.{Result, Response, Task}
import spray.http.Uri.Empty
import spray.http.StatusCodes

/**
 * User: david
 * Date: 15/03/14
 * Time: 15:18
 */

class MockResultFilter extends ResultFilter {
  override def handleResponse(r: Response) = {
    Some(new Response(new Task("handled", r.task.url, 0), StatusCodes.OK, r.headers, r.body))
  }

  override def handleResult(r: Result) = {
    Some(new Result(new Task("handled", r.task.url, 0), r.links))
  }
}

class ResultFilterTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  def initFilter() = {
    val filter = system.actorOf(Props[MockResultFilter])
    val left = TestProbe()
    val right = TestProbe()

    filter ! new LeftRight(left.ref, right.ref)
    expectMsg(Initialized)

    (filter, left, right)
  }

  "A RequestFilter" should {
    "apply filter to response and send it to right" in {
      val (filter, _, right) = initFilter()
      val input = new Response(new Task("unhandled", Empty, 0), StatusCodes.OK, Map(), "body")
      val expected = new Response(new Task("handled", Empty, 0), StatusCodes.OK, Map(), "body")

      filter ! input
      right.expectMsg(expected)
    }
    "apply filter to result and send it to left" in {
      val (filter, left, _) = initFilter()
      val input = new Result(new Task("unhandled", Empty, 0), Seq())
      val expected = new Result(new Task("handled", Empty, 0), Seq())

      filter ! input
      left.expectMsg(expected)
    }
  }
}
