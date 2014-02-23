package es.udc.prototype

import akka.actor.{Props, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{WordSpecLike, BeforeAndAfterAll, Matchers}
import com.typesafe.config.ConfigFactory

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:57
 */
class MasterTest extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("application.test.conf")))
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  "A Master actor" should {
    "store new tasks" in {
      val master = system.actorOf(Props[Master])
      val values = Seq("1","2")
      master ! new NewTasks(values)
      master ! new PullWork(2)
      expectMsgPF(){case Work(tasks) if tasks.toSet.equals(values.map(new Task(_)).toSet) => true}
    }
    "send only the number of tasks requested" in {
      val master = system.actorOf(Props[Master])
      val values = Seq("1","2")
      master ! new NewTasks(values)
      master ! new PullWork(1)
      expectMsgPF() {case Work(Seq(Task("1"))) => Unit
                     case Work(Seq(Task("2"))) => Unit}
    }
    "do not send work if there are not tasks to do" in {
      val master = system.actorOf(Props[Master])
      master ! PullWork(1)
      expectNoMsg()
    }
    "store links of a new task and set it as completed" in {
      val master = system.actorOf(Props[Master])
      val values = Seq("2","3")
      master ! new NewTasks(Seq("1"))
      master ! new Result("1", values)
      master ! new PullWork(2)
      expectMsgClass(classOf[Work]) match {
        case Work(tasks) =>
          tasks.toSet.equals(values.map(new Task(_)).toSet)
        case _ =>
          fail()
      }
    }
    "not store links of unknown task" in {
      val master = system.actorOf(Props[Master])
      val values = Seq("2","3")
      master ! new NewTasks(Seq("1"))
      master ! new Result("A", values)
      master ! new PullWork(2)
      expectMsg(new Work(Seq(new Task("1"))))
      master ! new PullWork(2)
      expectNoMsg()
    }
  }
}
