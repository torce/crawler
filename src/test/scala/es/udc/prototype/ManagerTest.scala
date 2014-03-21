package es.udc.prototype

import akka.testkit.{TestActorRef, TestProbe, ImplicitSender, TestKit}
import akka.actor._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.concurrent.duration._

/**
 * User: david
 * Date: 13/02/14
 * Time: 21:06
 */
class ProbesManager(config: Config, listener: ActorRef, master: ActorRef, downloader: ActorRef, crawler: ActorRef)
extends Manager(config, listener) {
  override def initMaster(config: Config, listener: ActorRef) = master
  override def initDownloader(config: Config) = downloader
  override def initCrawler(config: Config) = crawler
}

class MockChild extends Actor {
  def receive = {
    case e: Exception => throw e
  }
}

class MockSupervisorManager(config: Config, listener: ActorRef) extends Manager(config, listener) {
  override def initMaster(config: Config, listener: ActorRef) = context.actorOf(Props[MockChild])

  override def initDownloader(config: Config) = context.actorOf(Props[MockChild])

  override def initCrawler(config: Config) = context.actorOf(Props[MockChild])
}

class ManagerTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  val CONFIG = ConfigFactory.load("application.test.conf")
  val BATCH_SIZE = CONFIG.getInt("prototype.manager.batch-size")
  val RETRY_TIMEOUT = CONFIG.getInt("prototype.manager.retry-timeout").milliseconds

  val MSG_MAX_DELAY = 100.milliseconds

  override def afterAll() {
    system.shutdown()
  }

  def initManagerProbes(config: Config) = {
    val (master, downloader, crawler) = (TestProbe(), TestProbe(), TestProbe())
    val manager = system.actorOf(Props(classOf[ProbesManager], CONFIG, TestProbe().ref, master.ref, downloader.ref, crawler.ref))
    (manager, master, downloader, crawler)
  }

  def initManagerAct(config: Config) = {
    val managerRef = TestActorRef(Props(classOf[MockSupervisorManager], CONFIG, TestProbe().ref))
    val managerActor = managerRef.underlyingActor.asInstanceOf[MockSupervisorManager]
    (managerRef, managerActor.master, managerActor.downloader, managerActor.crawler)
  }

  "A Manager actor" should {
    "send work to downloader one by one in any order" in {
      val (manager, _, downloader, _) = initManagerProbes(CONFIG)

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
      val (manager, master, _, _) = initManagerProbes(CONFIG)

      manager ! new Work(Seq(new Task("id", "url")))
      master.expectMsg(new PullWork(BATCH_SIZE))
    }
    "retry requests to master after a timeout" in {
      val (_, master, _, _) = initManagerProbes(CONFIG)
      val msg = new PullWork(BATCH_SIZE)

      master.expectMsg(msg)
      master.expectMsg(RETRY_TIMEOUT + MSG_MAX_DELAY, msg)
    }
    "forward Result messages to master" in {
      val (manager, master, _, _) = initManagerProbes(CONFIG)
      val msg = new Result(new Task("id", "url"), Seq("1"))

      manager ! msg
      master.expectMsg(new PullWork(BATCH_SIZE))
      master.expectMsg(msg)
      master.sender should be(manager)
    }
    "forward Response messages to crawler" in {
      val (manager, _, _, crawler) = initManagerProbes(CONFIG)
      val msg = new Response(new Task("id", "url"), Map(), "body")

      manager ! msg
      crawler.expectMsg(msg)
    }
    "restart master when terminates" in {
      val (_, master, _, _) = initManagerAct(CONFIG)
      val listener = TestProbe()
      listener.watch(master)
      listener.expectNoMsg() //Must not receive Terminated msg
      master ! new Exception
      master ! new Identify(1)
      expectMsg(new ActorIdentity(1, Some(master)))
    }
    "restart downloader when terminates" in {
      val (_, _, downloader, _) = initManagerAct(CONFIG)
      val listener = TestProbe()
      listener.watch(downloader)
      listener.expectNoMsg() //Must not receive Terminated msg
      downloader ! new Exception
      downloader ! new Identify(1)
      expectMsg(new ActorIdentity(1, Some(downloader)))
    }
    "restart crawler when terminates" in {
      val (_, _, _, crawler) = initManagerAct(CONFIG)
      val listener = TestProbe()
      listener.watch(crawler)
      listener.expectNoMsg() //Must not receive Terminated msg
      crawler ! new Exception
      crawler ! new Identify(1)
      expectMsg(new ActorIdentity(1, Some(crawler)))
    }
  }
}