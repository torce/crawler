package es.udc.prototype.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.collection.JavaConversions._
import es.udc.prototype.{Error, Request, Response}
import spray.http.{StatusCode, Uri}
import es.udc.prototype.master.DefaultTask
import spray.http.Uri.Empty

class RetryHttpErrorTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  val config = ConfigFactory.load("application.test.conf")
  val errors = config.getIntList("prototype.retry-http-error.errors").toList
  val allowed = Seq(500, 403)

  def initRetry() = {
    val retry = system.actorOf(Props(classOf[RetryHttpError], config))
    val listener = TestProbe()
    val left = TestProbe()
    val right = TestProbe()
    listener.send(retry, new LeftRight(left.ref, right.ref))
    listener.expectMsg(Initialized)
    (retry, left, right)
  }

  "A RetryHttpErrorStage" should {
    "retry the configured http errors, sending again the Request" in {
      val (retry, left, right) = initRetry()
      val task = new DefaultTask("id", Uri.Empty, 0)
      errors.foreach {
        e =>
          left.send(retry, new Request(task, Map()))
          right.expectMsg(new Request(task, Map()))
          right.send(retry, new Response(task, StatusCode.int2StatusCode(e), Map(), ""))
          right.expectMsg(new Request(task, Map()))
      }
    }

    "allow the rest of the error codes" in {
      val (retry, left, right) = initRetry()
      val task = new DefaultTask("id", Uri.Empty, 0)
      allowed.foreach {
        e =>
          left.send(retry, new Request(task, Map()))
          right.expectMsg(new Request(task, Map()))
          right.send(retry, new Response(task, StatusCode.int2StatusCode(e), Map(), ""))
          left.expectMsg(new Response(task, StatusCode.int2StatusCode(e), Map(), ""))
      }
    }

    "drop unknown Responses with the configured error codes, it should be restarted later" in {
      val (retry, left, right) = initRetry()
      val task = new DefaultTask("id", Uri.Empty, 0)
      errors.foreach {
        e =>
          right.send(retry, new Response(task, StatusCode.int2StatusCode(e), Map(), ""))
          left.expectNoMsg()
      }
    }

    "allow unknown Responses if the error code is not among the error codes to drop" in {
      val (retry, left, right) = initRetry()
      val task = new DefaultTask("id", Uri.Empty, 0)
      allowed.foreach {
        e =>
          right.send(retry, new Response(task, StatusCode.int2StatusCode(e), Map(), ""))
          left.expectMsg(new Response(task, StatusCode.int2StatusCode(e), Map(), ""))
      }
    }

    "send all the error messages to the left" in {
      val (filter, left, _) = initRetry()
      val error = new Error(new DefaultTask("unhandled", Empty, 0), new Exception)

      filter ! error
      left.expectMsg(error)
    }
  }

}
