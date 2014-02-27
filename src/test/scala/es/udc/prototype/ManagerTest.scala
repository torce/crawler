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
      val tasks = Set(new Task("id1", "url1"), new Task("id2", "url2"))

      manager ! new Work(tasks.toSeq)

      downloader.expectMsgPF() {
        case Request(task, _) if tasks.contains(task) => Unit
      }
      downloader.expectMsgPF() {
        case Request(task, _) if tasks.contains(task) => Unit
      }
    }
    "request more work to master when empty" in {
      val master = TestProbe()
      val manager = system.actorOf(Props(classOf[Manager], master.ref, TestProbe().ref, TestProbe().ref))

      manager ! new Work(Seq(new Task("id", "url")))
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
      val msg = new Result(new Task("id", "url"), Seq("1"))

      manager ! msg
      master.expectMsg(new PullWork(Manager.BATCH_SIZE))
      master.expectMsg(msg)
      master.sender should be(manager)
    }
    "forward Response messages to crawler" in {
      val crawler = TestProbe()
      val manager = system.actorOf(Props(classOf[Manager], TestProbe().ref, TestProbe().ref, crawler.ref))
      val msg = new Response(new Task("id", "url"), Map(), "body")

      manager ! msg
      crawler.expectMsg(msg)
    }
  }
}
