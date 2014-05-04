package es.udc.prototype.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import es.udc.prototype.master.DefaultTask
import spray.http.{StatusCodes, StatusCode, Uri}
import es.udc.prototype.{Response, Request}

class CustomUserAgentTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  val config = ConfigFactory.load("application.test.conf")
  val configuredUserAgent = config.getString("prototype.custom-user-agent.user-agent")

  def initCustomUserAgent() = {
    val userAgent = system.actorOf(Props(classOf[CustomUserAgent], config))
    val listener = TestProbe()
    listener.send(userAgent, new LeftRight(listener.ref, listener.ref))
    listener.expectMsg(Initialized)
    (userAgent, listener)
  }

  "A CustomUserAgent stage" should {
    "set the configured User-Agent header in the Request" in {
      val (userAgent, listener) = initCustomUserAgent()
      val task = new DefaultTask("id", Uri.Empty, 0)
      listener.send(userAgent, new Request(task, Map()))
      listener.expectMsg(new Request(task, Map("User-Agent" -> configuredUserAgent)))
    }
    "do not modify the Responses" in {
      val (userAgent, listener) = initCustomUserAgent()
      val task = new DefaultTask("id", Uri.Empty, 0)
      listener.send(userAgent, new Response(task, StatusCodes.OK, Map(), ""))
      listener.expectMsg(new Response(task, StatusCodes.OK, Map(), ""))
    }
  }
}
