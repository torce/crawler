package es.udc.prototype

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * User: david
 * Date: 13/02/14
 * Time: 21:06
 */
class ManagerTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  val MSG_MAX_DELAY = 100 milliseconds

  override def afterAll() {
    system.shutdown()
  }

  "A Manager actor" should {
    "send work to downloader one by one in any order" in {
      val downloader = TestProbe()
      val manager = system.actorOf(Props(classOf[Manager], TestProbe().ref, downloader.ref, TestProbe().ref))
      val tasks = Seq(new Task("1"), new Task("2"))

      manager ! new Work(tasks)

      downloader.expectMsgPF() {case Request("1", "1", _) => Unit
                                case Request("2", "2", _) => Unit}
      downloader.expectMsgPF() {case Request("1", "1", _) => Unit
                                case Request("2", "2", _) => Unit}
    }
    "request more work to master when empty" in {
      val master = TestProbe()
      val manager = system.actorOf(Props(classOf[Manager], master.ref, TestProbe().ref, TestProbe().ref))

      manager ! new Work(Seq(new Task("1")))
      master.expectMsg(new PullWork(Manager.BATCH_SIZE))
    }
    "retry requests to master after a timeout" in {
      val master = TestProbe()
      val manager = system.actorOf(Props(classOf[Manager], master.ref, TestProbe().ref, TestProbe().ref))
      val msg = new PullWork(Manager.BATCH_SIZE)

      master.expectMsg(msg)
      master.expectMsg(Manager.RETRY_TIMEOUT + MSG_MAX_DELAY, msg)
    }
    "forward Result messages to master" in {
      val master = TestProbe()
      val manager = system.actorOf(Props(classOf[Manager], master.ref, TestProbe().ref, TestProbe().ref))
      val msg = new Result("id", Seq("1"))

      manager ! msg
      master.expectMsg(new PullWork(Manager.BATCH_SIZE))
      master.expectMsg(msg)
    }
    "forward Response messages to crawler" in {
      val crawler = TestProbe()
      val manager = system.actorOf(Props(classOf[Manager], TestProbe().ref, TestProbe().ref, crawler.ref))
      val msg = new Response("url", "id", Map(), "body")

      manager ! msg
      crawler.expectMsg(msg)
    }
  }
}
