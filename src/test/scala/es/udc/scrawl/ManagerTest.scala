package es.udc.scrawl

import akka.testkit._
import akka.actor._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.concurrent.duration._
import akka.actor.ActorIdentity
import scala.Some
import akka.actor.Identify
import es.udc.scrawl.pipeline.{PipelineRestarting, PipelineStarted, ToLeft, ToRight}
import spray.http.{StatusCodes, Uri}
import es.udc.scrawl.master.DefaultTask

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
  val BATCH_SIZE = CONFIG.getInt("scrawl.manager.batch-size")
  val RETRY_TIMEOUT = CONFIG.getInt("scrawl.manager.retry-timeout").milliseconds

  val MSG_MAX_DELAY = 100.milliseconds

  override def afterAll() {
    system.shutdown()
  }

  /**
   * Initializes the manager actor in a active state.
   * Injects TestProbe instead of child actors.
   * @param config Configuration parameter for manager actor.
   * @return A Tuple containing the manager as first element and
   *         TestProbe of downloader, crawler, requestPipeline and resultPipeline
   */
  def initManagerActive(config: Config) = {
    val (manager, master, downloader, crawler, requestPipe, resultPipe) = initManagerCreated(config)
    requestPipe.send(manager, PipelineStarted)
    requestPipe.setAutoPilot(new PipelineAutoPilot(requestPipe.ref))

    resultPipe.send(manager, PipelineStarted)
    resultPipe.setAutoPilot(new PipelineAutoPilot(resultPipe.ref))

    (manager, master, downloader, crawler, requestPipe, resultPipe)
  }

  def initManagerCreated(config: Config) = {
    val (master, downloader, crawler, requestPipe, resultPipe) =
      (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val manager = system.actorOf(
      Props(classOf[ProbesManager], CONFIG, Actor.noSender, master.ref,
        downloader.ref, crawler.ref, requestPipe.ref, resultPipe.ref))
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
      val (manager, _, downloader, _, requestPipeline, _) = initManagerActive(CONFIG)

      val tasks = Set[Task](new DefaultTask("id1", "url1", 0), new DefaultTask("id2", "url2", 0))

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
      val (manager, master, _, _, _, _) = initManagerActive(CONFIG)

      manager ! new Work(Seq(new DefaultTask("id", "url", 0)))
      master.expectMsg(new PullWork(BATCH_SIZE))
    }
    "retry requests to master after a timeout" in {
      val (_, master, _, _, _, _) = initManagerActive(CONFIG)
      val msg = new PullWork(BATCH_SIZE)

      master.expectMsg(msg)
      master.expectMsg(RETRY_TIMEOUT + MSG_MAX_DELAY, msg)
    }
    "forward Result messages from crawler to master through the result pipeline" in {
      val (manager, master, _, crawler, _, resultPipeline) = initManagerActive(CONFIG)
      val msg = new Result(new DefaultTask("id", "url", 0), Seq("1"))

      crawler.send(manager, msg)
      resultPipeline.expectMsg(ToLeft(msg))

      master.expectMsg(new PullWork(BATCH_SIZE)) //The initial work request
      master.expectMsg(msg)
      master.sender should be(manager)
    }
    "forward Error messages from crawler to master through the result pipeline" in {
      val (manager, master, _, crawler, _, resultPipeline) = initManagerActive(CONFIG)
      val msg = new Error(new DefaultTask("id", "url", 0), new Exception)

      crawler.send(manager, msg)
      resultPipeline.expectMsg(ToLeft(msg))

      master.expectMsg(new PullWork(BATCH_SIZE)) //The initial work request
      master.expectMsg(msg)
      master.sender should be(manager)
    }
    "forward Response messages from downloader to crawler through the request and the result pipeline" in {
      val (manager, _, downloader, crawler, requestPipeline, resultPipeline) = initManagerActive(CONFIG)
      val msg = new Response(new DefaultTask("id", "url", 0), StatusCodes.OK, Map(), "body")

      downloader.send(manager, msg)

      requestPipeline.expectMsg(ToLeft(msg))
      resultPipeline.expectMsg(ToRight(msg))

      crawler.expectMsg(msg)
    }
    "forward Error messages from downloader to master through the request pipeline" in {
      val (manager, master, downloader, _, requestPipeline, resultPipeline) = initManagerActive(CONFIG)
      val msg = new Error(new DefaultTask("id", "url", 0), new Exception)

      downloader.send(manager, msg)

      requestPipeline.expectMsg(ToLeft(msg))
      resultPipeline.expectNoMsg()

      master.expectMsg(new PullWork(BATCH_SIZE)) //The initial work request
      master.expectMsg(msg)
      master.sender should be(manager)
    }
    "forward Request messages to crawler through the request pipeline" in {
      val (manager, master, downloader, crawler, requestPipeline, resultPipeline) = initManagerActive(CONFIG)
      val msg = new Request(new DefaultTask("id", "url", 0), Map())

      master.expectMsg(new PullWork(BATCH_SIZE)) //The initial work request
      master.send(manager, new Work(Seq(msg.task)))

      requestPipeline.expectMsg(ToRight(msg))

      downloader.expectMsg(msg)
    }
  }
  "A Manager, as supervisor" should {
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
    "restart requestPipeline when terminates" in {
      val (_, _, _, _, requestPipeline, _) = initManagerAct(CONFIG)
      val listener = TestProbe()
      listener.watch(requestPipeline)
      listener.expectNoMsg() //Must not receive Terminated msg
      requestPipeline ! new Exception
      requestPipeline ! new Identify(1)
      expectMsg(new ActorIdentity(1, Some(requestPipeline)))
    }
    "restart resultPipeline when terminates" in {
      val (_, _, _, _, _, resultPipeline) = initManagerAct(CONFIG)
      val listener = TestProbe()
      listener.watch(resultPipeline)
      listener.expectNoMsg() //Must not receive Terminated msg
      resultPipeline ! new Exception
      resultPipeline ! new Identify(1)
      expectMsg(new ActorIdentity(1, Some(resultPipeline)))
    }
  }
  "A Manager in Created state" should {
    "store the work sent by master" in {
      val (manager, master, _, _, requestPipe, _) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      master.send(manager, new Work(Seq(task)))
      requestPipe.send(manager, PipelineStarted)
      requestPipe.expectMsg(new ToRight(new Request(task, Map())))
    }
    "store the responses sent by the downloader" in {
      val (manager, _, downloader, _, requestPipe, _) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      downloader.send(manager, new Response(task, StatusCodes.OK, Map(), ""))
      requestPipe.send(manager, PipelineStarted)
      requestPipe.expectMsg(new ToLeft(new Response(task, StatusCodes.OK, Map(), "")))
    }
    "store the results sent by the crawler" in {
      val (manager, _, _, crawler, _, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      crawler.send(manager, new Result(task, Seq()))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsg(new ToLeft(new Result(task, Seq())))
    }
    "store errors sent by the downloader" in {
      val (manager, _, downloader, _, requestPipe, _) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      downloader.send(manager, new Error(task, new Exception))
      requestPipe.send(manager, PipelineStarted)
      requestPipe.expectMsgPF() {
        case ToLeft(Error(`task`, _)) => Unit
      }
    }
    "store errors sent by the crawler" in {
      val (manager, master, _, crawler, _, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      crawler.send(manager, new Error(task, new Exception))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsgPF() {
        case ToLeft(Error(`task`, _)) => Unit
      }
    }
  }
  "A Manager in ResultPipelineActive state" should {
    "store the work sent by master when the request pipeline is not ready" in {
      val (manager, master, _, _, requestPipe, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)
      resultPipe.send(manager, PipelineStarted)

      master.send(manager, new Work(Seq(task)))
      requestPipe.send(manager, PipelineStarted)
      requestPipe.expectMsg(new ToRight(new Request(task, Map())))
    }
    "store the responses sent by the downloader when the request pipeline is not ready" in {
      val (manager, _, downloader, _, requestPipe, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)
      resultPipe.send(manager, PipelineStarted)

      downloader.send(manager, new Response(task, StatusCodes.OK, Map(), ""))
      requestPipe.send(manager, PipelineStarted)
      requestPipe.expectMsg(new ToLeft(new Response(task, StatusCodes.OK, Map(), "")))
    }
    "store the errors sent by the downloader when the request pipeline is not ready" in {
      val (manager, _, downloader, _, requestPipe, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)
      resultPipe.send(manager, PipelineStarted)

      downloader.send(manager, new Error(task, new Exception))
      requestPipe.send(manager, PipelineStarted)
      requestPipe.expectMsgPF() {
        case ToLeft(Error(`task`, _)) => Unit
      }
    }
  }
  "A Manager in RequestPipelineActive state" should {
    "store the work sent by master when the result pipeline is not ready" in {
      val (manager, master, downloader, _, requestPipe, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)
      requestPipe.send(manager, PipelineStarted)

      master.send(manager, new Work(Seq(task)))
      requestPipe.expectMsg(new ToRight(new Request(task, Map())))
      requestPipe.reply(new Request(task, Map()))
      downloader.expectMsg(new Request(task, Map()))
      downloader.reply(new Response(task, StatusCodes.OK, Map(), ""))
      requestPipe.expectMsg(new ToLeft(new Response(task, StatusCodes.OK, Map(), "")))
      requestPipe.reply(new Response(task, StatusCodes.OK, Map(), ""))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsg(new ToRight(new Response(task, StatusCodes.OK, Map(), "")))
    }
    "store the responses sent by the request pipeline when the result pipeline is not ready" in {
      val (manager, _, _, _, requestPipe, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)
      requestPipe.send(manager, PipelineStarted)

      requestPipe.send(manager, new Response(task, StatusCodes.OK, Map(), ""))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsg(new ToRight(new Response(task, StatusCodes.OK, Map(), "")))
    }
    "forward the errors sent by the request pipeline to master when the result pipeline is not ready" in {
      val (manager, master, _, _, requestPipe, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)
      requestPipe.send(manager, PipelineStarted)

      requestPipe.send(manager, new Error(task, new Exception))
      resultPipe.send(manager, PipelineStarted)
      master.expectMsg(new PullWork(BATCH_SIZE)) //Initial work request
      master.expectMsgPF() {
        case Error(`task`, _) => Unit
      }
      resultPipe.expectNoMsg()
    }
    "store the results sent by the crawler when the result pipeline is not ready" in {
      val (manager, _, _, crawler, requestPipe, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)
      requestPipe.send(manager, PipelineStarted)

      crawler.send(manager, new Result(task, Seq()))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsg(new ToLeft(new Result(task, Seq())))
    }
    "store the errors sent by the crawler when the result pipeline is not ready" in {
      val (manager, _, _, crawler, requestPipe, resultPipe) = initManagerCreated(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)
      requestPipe.send(manager, PipelineStarted)

      crawler.send(manager, new Error(task, new Exception))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsgPF() {
        case ToLeft(Error(`task`, _)) => Unit
      }
    }
  }
  "A Manager in Active state" should {
    "store the work sent by master when the request pipeline is restarting" in {
      val (manager, master, _, _, requestPipe, _) = initManagerActive(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      requestPipe.send(manager, PipelineRestarting)
      master.send(manager, new Work(Seq(task)))
      requestPipe.expectNoMsg()
      requestPipe.send(manager, PipelineStarted)
      requestPipe.expectMsg(new ToRight(new Request(task, Map())))
    }
    "store the work sent by master when the result pipeline is restarting" in {
      val (manager, master, downloader, _, requestPipe, resultPipe) = initManagerActive(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      resultPipe.send(manager, PipelineRestarting)
      master.send(manager, new Work(Seq(task)))
      requestPipe.expectMsg(new ToRight(new Request(task, Map())))
      requestPipe.reply(new Request(task, Map()))
      downloader.expectMsg(new Request(task, Map()))
      downloader.reply(new Response(task, StatusCodes.OK, Map(), ""))
      requestPipe.expectMsg(new ToLeft(new Response(task, StatusCodes.OK, Map(), "")))
      requestPipe.reply(new Response(task, StatusCodes.OK, Map(), ""))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsg(new ToRight(new Response(task, StatusCodes.OK, Map(), "")))
    }
    "store the responses sent by the downloader when the request pipeline is restarting" in {
      val (manager, _, downloader, _, requestPipe, resultPipe) = initManagerActive(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      resultPipe.send(manager, PipelineRestarting)
      downloader.send(manager, new Response(task, StatusCodes.OK, Map(), ""))
      requestPipe.send(manager, PipelineStarted)
      requestPipe.expectMsg(new ToLeft(new Response(task, StatusCodes.OK, Map(), "")))
    }
    "store the responses sent by the request pipeline when the result pipeline is restarting" in {
      val (manager, _, _, _, requestPipe, resultPipe) = initManagerActive(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      resultPipe.send(manager, PipelineRestarting)
      requestPipe.send(manager, new Response(task, StatusCodes.OK, Map(), ""))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsg(new ToRight(new Response(task, StatusCodes.OK, Map(), "")))
    }
    "store the results sent by the crawler when the result pipeline is restarting" in {
      val (manager, _, _, crawler, _, resultPipe) = initManagerActive(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      resultPipe.send(manager, PipelineRestarting)
      crawler.send(manager, new Result(task, Seq()))
      resultPipe.send(manager, PipelineStarted)
      resultPipe.expectMsg(new ToLeft(new Result(task, Seq())))
    }

    "return to the initial state if both pipelines are restarting" in {
      val (manager, master, downloader, crawler, requestPipe, resultPipe) = initManagerActive(CONFIG)
      val task = new DefaultTask("id", Uri.Empty, 0)

      requestPipe.send(manager, PipelineRestarting)
      resultPipe.send(manager, PipelineRestarting)
      master.send(manager, new Work(Seq(task)))
      downloader.send(manager, new Response(task, StatusCodes.OK, Map(), ""))
      crawler.send(manager, new Result(task, Seq()))
      requestPipe.expectNoMsg()
      resultPipe.expectNoMsg()
    }
  }
}