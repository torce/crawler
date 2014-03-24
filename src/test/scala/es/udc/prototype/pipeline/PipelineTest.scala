package es.udc.prototype.pipeline

import akka.testkit.{TestActorRef, TestProbe, ImplicitSender, TestKit}
import akka.actor.{ActorRef, Props, Actor, ActorSystem}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.concurrent.duration._

/**
 * User: david
 * Date: 15/03/14
 * Time: 16:11
 */

object MockPipeline {
  // The created stages are accessible from the outside for testing
  var stages: Seq[ActorRef] = _
}

class MockPipeline(config: Config, stages: Seq[Props]) extends Pipeline(config) {
  override def initStages(config: Config) = {
    MockPipeline.stages = stages.map(context.actorOf)
    MockPipeline.stages
  }
}

class MockProbesPipeline(config: Config, probes: Seq[ActorRef]) extends Pipeline(config) {
  override def initStages(config: Config) = probes
}

class PlusOneKillableStage extends Stage {
  override def active = {
    case i: Int => right ! (i + 1)
    case e: Exception => throw e
  }
}


// Hack to place a TestProbe (proxy) between a parent and a child actor
class MockParent(proxy: ActorRef, childProps: Props) extends Actor {
  var child: ActorRef = _

  override def preStart() {
    child = context.actorOf(childProps, "request-pipeline")
  }

  def receive = {
    case m if sender == child => proxy forward m
    case m => child ! m
  }

}

class PipelineTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  val config = ConfigFactory.load("application.test.conf")
  val retryTimeout = config.getInt("prototype.request-pipeline.retry-timeout").milliseconds
  val maxMsgDelay = 100.milliseconds

  def initPipeline(stage: Props, number: Int) = {
    val proxy = TestProbe()
    val stages = for (_ <- 1 to number) yield {
      stage
    }
    val pipeline = system.actorOf(Props(classOf[MockParent], proxy.ref, Props(classOf[MockPipeline], config, stages)))
    (pipeline, proxy)
  }

  "A Pipeline" should {
    "send a PipelineStarted message when all the stages are active" in {
      val (_, proxy) = initPipeline(Props[PlusOneKillableStage], 5)
      proxy.expectMsg(PipelineStarted)
    }

    "send messages through all their children" in {
      val (parent, proxy) = initPipeline(Props[PlusOneKillableStage], 5)

      proxy.expectMsg(PipelineStarted)
      proxy.send(parent, new ToRight(0))
      proxy.expectMsg(MockPipeline.stages.size)
    }

    "restart the stages individually in case of any error" in {
      val (parent, proxy) = initPipeline(Props[PlusOneKillableStage], 5)

      proxy.expectMsg(PipelineStarted)

      for (i <- 0 to MockPipeline.stages.size - 1) {
        val listener = TestProbe()
        listener.watch(MockPipeline.stages(i))
        listener.expectNoMsg()

        MockPipeline.stages(i) ! new Exception
        proxy.expectMsg(PipelineRestarting)
        proxy.expectMsg(PipelineStarted)
        proxy.send(parent, new ToRight(0))
        proxy.expectMsg(MockPipeline.stages.size)
      }
    }

    "retry the stage initialization after a timeout" in {
      val proxy = TestProbe()
      val stage = TestProbe()
      val parent = TestActorRef(Props(classOf[MockParent], proxy.ref,
        Props(classOf[MockProbesPipeline], config, Seq(stage.ref))))
      val pipeline = parent.underlyingActor.asInstanceOf[MockParent].child

      stage.expectMsg(LeftRight(pipeline, pipeline))
      stage.expectMsg(retryTimeout + maxMsgDelay, LeftRight(pipeline, pipeline))
      stage.reply(Initialized)
      proxy.expectMsg(PipelineStarted)
    }
  }
}
