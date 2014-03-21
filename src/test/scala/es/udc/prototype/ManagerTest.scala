package es.udc.prototype

import akka.testkit._
import akka.actor._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.concurrent.duration._
import akka.actor.ActorIdentity
import scala.Some
import akka.actor.Identify
import es.udc.prototype.pipeline.{ToLeft, ToRight}

/**
 * User: david
 * Date: 13/02/14
 * Time: 21:06
 */
class ProbesManager(config: Config, listener: ActorRef, master: ActorRef, downloader: ActorRef,
                    crawler: ActorRef, requestPipeline: ActorRef, resultPipeline: ActorRef)
  extends Manager(config, listener) {
  override def initMaster(config: Config, listener: ActorRef) = master

  override def initDownloader(config: Config) = downloader

  override def initCrawler(config: Config) = crawler

  override def initRequestPipeline(config: Config) = requestPipeline

  override def initResultPipeline(config: Config) = resultPipeline
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

  override def initRequestPipeline(config: Config) = context.actorOf(Props[MockChild])

  override def initResultPipeline(config: Config) = context.actorOf(Props[MockChild])
}

class PipelineAutoPilot(testActor: ActorRef) extends TestActor.AutoPilot {
  override def run(sender: ActorRef, msg: Any) = {
    msg match {
      case ToRight(m) =>
        sender.tell(m, testActor)
        TestActor.KeepRunning
      case ToLeft(m) =>
        sender.tell(m, testActor)
        TestActor.KeepRunning
    }
  }
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
    val (master, downloader, crawler, requestPipe, resultPipe) =
      (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val manager = system.actorOf(
      Props(classOf[ProbesManager], CONFIG, Actor.noSender, master.ref,
        downloader.ref, crawler.ref, requestPipe.ref, resultPipe.ref))

    requestPipe.setAutoPilot(new PipelineAutoPilot(requestPipe.ref))
    resultPipe.setAutoPilot(new PipelineAutoPilot(resultPipe.ref))

    (manager, master, downloader, crawler, requestPipe, resultPipe)
  }

  def initManagerAct(config: Config) = {
    val managerRef = TestActorRef(Props(classOf[MockSupervisorManager], CONFIG, Actor.noSender))
    val managerActor = managerRef.underlyingActor.asInstanceOf[MockSupervisorManager]
    (managerRef, managerActor.master, managerActor.downloader, managerActor.crawler,
      managerActor.requestPipeline, managerActor.resultPipeline)
  }

  "A Manager actor" should {
    "send work to downloader through the request pipeline one by one in any order" in {
      val (manager, _, downloader, _, requestPipeline, _) = initManagerProbes(CONFIG)

      val tasks = Set(new Task("id1", "url1", 0), new Task("id2", "url2", 0))

      manager ! new Work(tasks.toSeq)

      requestPipeline.expectMsgPF() {
        case ToRight(Request(task, _)) if tasks.contains(task) => Unit
      }
      downloader.expectMsgPF() {
        case Request(task, _) if tasks.contains(task) => Unit
      }
      downloader.expectMsgPF() {
        case Request(task, _) if tasks.contains(task) => Unit
      }
    }
    "request more work to master when empty" in {
      val (manager, master, _, _, _, _) = initManagerProbes(CONFIG)

      manager ! new Work(Seq(new Task("id", "url", 0)))
      master.expectMsg(new PullWork(BATCH_SIZE))
    }
    "retry requests to master after a timeout" in {
      val (_, master, _, _, _, _) = initManagerProbes(CONFIG)
      val msg = new PullWork(BATCH_SIZE)

      master.expectMsg(msg)
      master.expectMsg(RETRY_TIMEOUT + MSG_MAX_DELAY, msg)
    }
    "forward Result messages to master through the result pipeline" in {
      val (manager, master, _, crawler, _, resultPipeline) = initManagerProbes(CONFIG)
      val msg = new Result(new Task("id", "url", 0), Seq("1"))

      crawler.send(manager, msg)
      resultPipeline.expectMsg(ToLeft(msg))

      master.expectMsg(new PullWork(BATCH_SIZE)) //The initial work request
      master.expectMsg(msg)
      master.sender should be(manager)
    }
    "forward Response messages from downloader to crawler through the request and the result pipeline" in {
      val (manager, _, downloader, crawler, requestPipeline, resultPipeline) = initManagerProbes(CONFIG)
      val msg = new Response(new Task("id", "url", 0), Map(), "body")

      downloader.send(manager, msg)

      requestPipeline.expectMsg(ToLeft(msg))
      resultPipeline.expectMsg(ToRight(msg))

      crawler.expectMsg(msg)
    }
    "forward Request messages to crawler through the request pipeline" in {
      val (manager, master, downloader, crawler, requestPipeline, resultPipeline) = initManagerProbes(CONFIG)
      val msg = new Request(new Task("id", "url", 0), Map())

      master.expectMsg(new PullWork(BATCH_SIZE)) //The initial work request
      master.send(manager, new Work(Seq(msg.task)))

      requestPipeline.expectMsg(ToRight(msg))

      downloader.expectMsg(msg)
    }
    "restart master when terminates" in {
      val (_, master, _, _, _, _) = initManagerAct(CONFIG)
      val listener = TestProbe()
      listener.watch(master)
      listener.expectNoMsg() //Must not receive Terminated msg
      master ! new Exception
      master ! new Identify(1)
      expectMsg(new ActorIdentity(1, Some(master)))
    }
    "restart downloader when terminates" in {
      val (_, _, downloader, _, _, _) = initManagerAct(CONFIG)
      val listener = TestProbe()
      listener.watch(downloader)
      listener.expectNoMsg() //Must not receive Terminated msg
      downloader ! new Exception
      downloader ! new Identify(1)
      expectMsg(new ActorIdentity(1, Some(downloader)))
    }
    "restart crawler when terminates" in {
      val (_, _, _, crawler, _, _) = initManagerAct(CONFIG)
      val listener = TestProbe()
      listener.watch(crawler)
      listener.expectNoMsg() //Must not receive Terminated msg
      crawler ! new Exception
      crawler ! new Identify(1)
      expectMsg(new ActorIdentity(1, Some(crawler)))
    }
  }
}