package es.udc.prototype.pipeline

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import es.udc.prototype.{Task, Result}
import spray.http.Uri

/**
 * User: david
 * Date: 21/03/14
 * Time: 18:42
 */
class MaxDepthFilterTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  //Override the max-depth property for testing
  val config = ConfigFactory.parseString("prototype.max-depth-filter.max-depth = 1")
    .withFallback(ConfigFactory.load("application.test.conf"))
  val maxDepth = 1

  def initMaxDepth() = {
    val maxDepth = system.actorOf(Props(classOf[MaxDepthFilter], config))
    val listener = TestProbe()
    listener.send(maxDepth, new LeftRight(listener.ref, listener.ref))
    listener.expectMsg(Initialized)
    (maxDepth, listener)
  }

  "A MaxDeptFilter" should {
    "not allow links in Result with depth greater than or equals the max depth" in {
      val (maxDepth, listener) = initMaxDepth()

      val links = Seq(Uri("http://test.test"), Uri("http://es.test"))

      //Equal
      listener.send(maxDepth, new Result(new Task("id", Uri.Empty, 1), links))
      listener.expectMsg(new Result(new Task("id", Uri.Empty, 1), Seq()))

      //Greater than
      listener.send(maxDepth, new Result(new Task("id", Uri.Empty, 2), links))
      listener.expectMsg(new Result(new Task("id", Uri.Empty, 2), Seq()))
    }
    "not modify links in Results with depth below the max depth" in {
      val (maxDepth, listener) = initMaxDepth()

      val links = Seq(Uri("http://test.test"), Uri("http://es.test"))

      listener.send(maxDepth, new Result(new Task("id", Uri.Empty, 0), links))
      listener.expectMsg(new Result(new Task("id", Uri.Empty, 0), links))
    }
  }
}
