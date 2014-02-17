package es.udc.prototype

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, BeforeAndAfterAll, WordSpecLike}

/**
 * User: david
 * Date: 14/02/14
 * Time: 14:57
 */
class NodeTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  override def afterAll {
  TestKit.shutdownActorSystem(ActorSystem("TestSystem"))
  }

  "The tasks in a node" should {
    "be retrieved from the Master, scheduled in the manager" in {

    }
  }

}