package es.udc.prototype.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{ActorRef, Props, Actor, ActorSystem}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
 * User: david
 * Date: 15/03/14
 * Time: 16:11
 */

object MockStartPlusOneStages {
  val numberOfStages = 5
  // The created stages are accessible from the outside for testing
  var stages: Seq[ActorRef] = _
}

trait MockStartPlusOneStages {
  this: Actor with StartStages =>
  override def initStages(config: Config) = {
    MockStartPlusOneStages.stages = for (i <- 0 to MockStartPlusOneStages.numberOfStages - 1)
    yield {
      context.actorOf(Props[PlusOneKillableStage])
    }
    MockStartPlusOneStages.stages
  }
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
    child = context.actorOf(childProps)
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

  val CONFIG = ConfigFactory.load("application.test.conf")

  "A Pipeline" should {
    "send a PipelineStarted message when all the stages are active" in {
      val proxy = TestProbe()
      system.actorOf(Props(classOf[MockParent], proxy.ref, Props(new Pipeline(CONFIG) with MockStartPlusOneStages)))
      proxy.expectMsg(PipelineStarted)
    }

    "send messages through all their children" in {
      val proxy = TestProbe()
      val parent = system.actorOf(Props(classOf[MockParent], proxy.ref, Props(new Pipeline(CONFIG) with MockStartPlusOneStages)))

      proxy.expectMsg(PipelineStarted)
      proxy.send(parent, 0)
      proxy.expectMsg(MockStartPlusOneStages.numberOfStages)
    }

    "restart the stages individually in case of any error" in {
      val proxy = TestProbe()
      val parent = system.actorOf(Props(classOf[MockParent], proxy.ref, Props(new Pipeline(CONFIG) with MockStartPlusOneStages)))

      proxy.expectMsg(PipelineStarted)

      for (i <- 0 to MockStartPlusOneStages.numberOfStages - 1) {
        val listener = TestProbe()
        listener.watch(MockStartPlusOneStages.stages(i))
        listener.expectNoMsg()

        MockStartPlusOneStages.stages(i) ! new Exception
        proxy.expectMsg(PipelineRestarting)
        proxy.expectMsg(PipelineStarted)
        proxy.send(parent, 0)
        proxy.expectMsg(MockStartPlusOneStages.numberOfStages)
      }
    }
  }
}
