package es.udc.prototype

import akka.actor.{Props, ActorSystem}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
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
      val master = system.actorOf(Props(classOf[Master], TestProbe().ref))
      val values = Set("1", "2")
      master ! new NewTasks(values.toSeq)
      master ! new PullWork(2)
      expectMsgPF() {
        case Work(tasks) if tasks.map(_.url).forall(values.contains) => Unit
      }
    }
    "send only the number of tasks requested" in {
      val master = system.actorOf(Props(classOf[Master], TestProbe().ref))
      val values = Seq("1","2")
      master ! new NewTasks(values)
      master ! new PullWork(1)
      expectMsgPF() {
        case Work(Seq(Task(_, url))) if values.contains(url) => Unit
      }
    }
    "do not send work if there are not tasks to do" in {
      val master = system.actorOf(Props(classOf[Master], TestProbe().ref))
      master ! PullWork(1)
      expectNoMsg()
    }
    "store links of a new task and set it as completed" in {
      val master = system.actorOf(Props(classOf[Master], TestProbe().ref))
      val values = Set("2", "3")
      master ! new NewTasks(Seq("1"))
      master ! new PullWork(1)
      expectMsgPF() {
        case Work(Seq(Task(_, "1"))) => Unit
      }
      master ! new Result(new Task(Master.generateId("1"), "1"), values.toSeq)
      master ! new PullWork(2)
      expectMsgPF() {
        case Work(tasks) if tasks.map(_.url).forall(values.contains) => Unit
      }
    }
    "not store links of unknown task" in {
      val master = system.actorOf(Props(classOf[Master], TestProbe().ref))
      val values = Seq("2","3")
      master ! new NewTasks(Seq("1"))
      master ! new Result(new Task("id", "url"), values)
      master ! new PullWork(2)
      expectMsgPF() {
        case Work(Seq(Task(_, "1"))) => Unit
      }
      master ! new PullWork(2)
      expectNoMsg()
    }
  }
}
