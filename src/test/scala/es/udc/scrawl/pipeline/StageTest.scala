package es.udc.scrawl.pipeline

import akka.actor.{Actor, ActorSystem}
import com.typesafe.config.ConfigFactory
import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
 * User: david
 * Date: 15/03/14
 * Time: 13:34
 */

class StageTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  "A PipelineStage" should {
    "send a message after storing their left and right stages" in {
      val stage = TestActorRef(new Stage {
        override def active: Actor.Receive = Actor.emptyBehavior
      })

      val left = TestProbe()
      val right = TestProbe()

      stage ! new LeftRight(left.ref, right.ref)
      expectMsg(Initialized)
      stage.underlyingActor.left should be(left.ref)
      stage.underlyingActor.right should be(right.ref)
    }
  }
}
