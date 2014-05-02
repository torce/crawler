package es.udc.prototype.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.collection.JavaConversions._
import es.udc.prototype.{Request, Response}
import spray.http.{StatusCode, Uri}
import es.udc.prototype.master.DefaultTask

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

    "drop unknown Responses, it should be restarted later" in {
      val (retry, left, right) = initRetry()
      val task = new DefaultTask("id", Uri.Empty, 0)
      right.send(retry, new Response(task, 200, Map(), ""))
      left.expectNoMsg()
    }
  }

}
