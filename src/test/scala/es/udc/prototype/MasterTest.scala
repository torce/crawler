package es.udc.prototype

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.testkit.{TestActorRef, TestProbe, ImplicitSender, TestKit}
import org.scalatest.{WordSpecLike, BeforeAndAfterAll, Matchers}
import com.typesafe.config.{Config, ConfigFactory}
import spray.http.Uri
import scala.collection.mutable.{Map => MMap}

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:57
 */
class MockMaster(config: Config, listener: ActorRef) extends Master(config, listener) {
  def storage = taskStorage
}

class MasterTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  val config = ConfigFactory.load("application.test.conf")

  override def afterAll() {
    system.shutdown()
  }

  def initMaster() = {
    val listener = TestProbe()
    val master = system.actorOf(Props(classOf[Master], config, listener.ref))
    listener.expectMsg(Started)
    (master, listener)
  }

  "A Master actor" should {
    "send a Started message to listener after start" in {
      system.actorOf(Props(classOf[Master], config, self))
      expectMsg(Started)
    }
    "store new tasks" in {
      val (master, _) = initMaster()
      val values = Set(Uri("http://test1.test"), Uri("http://test2.test"))
      master ! new NewTasks(values.toSeq)
      master ! new PullWork(2)
      expectMsgPF() {
        case Work(tasks) if tasks.map(_.url).forall(values.contains) => Unit
      }
    }
    "send only the number of tasks requested" in {
      val (master, _) = initMaster()
      val values = Seq(Uri("http://test1.test"), Uri("http://test2.test"))
      master ! new NewTasks(values)
      master ! new PullWork(1)
      expectMsgPF() {
        case Work(Seq(Task(_, url, 0))) if values.contains(url) => Unit
      }
    }
    "do not send work if there are not tasks to do" in {
      val (master, _) = initMaster()
      master ! PullWork(1)
      expectNoMsg()
    }
    "store links of a new task and set it as completed" in {
      val (master, _) = initMaster()
      val completedTask = Uri("http://test1.test")
      val childTasks = Set(Uri("http://test2.test"), Uri("http://test3.test"))
      master ! new NewTasks(Seq(completedTask))
      master ! new PullWork(1)
      expectMsgPF() {
        case Work(Seq(Task(_, `completedTask`, 0))) => Unit
      }
      master ! new Result(new Task(Master.generateId(completedTask), completedTask, 0), childTasks.toSeq)
      master ! new PullWork(2)

      //Check that the Uris and depth are the expected
      expectMsgPF() {
        case Work(tasks) if tasks.map(_.url).forall(childTasks.contains) && tasks.forall(_.depth == 1) => Unit
      }
    }
    "not store links of unknown task" in {
      val (master, _) = initMaster()
      val unknownTask = Uri("http://unknown.test")
      val firstTask = Uri("http://task.test")
      val values = Seq(Uri("http://test2.test"), Uri("http://test3.test"))
      master ! new NewTasks(Seq(firstTask))
      master ! new Result(new Task(Master.generateId(unknownTask), unknownTask, 0), values)
      master ! new PullWork(2)
      expectMsgPF() {
        case Work(Seq(Task(_, `firstTask`, 0))) => Unit
      }
      master ! new PullWork(2)
      expectNoMsg()
    }
    "send a Finished message to listener when all the tasks are done" in {
      val master = system.actorOf(Props(classOf[Master], config, self))
      val task = new Task(Master.generateId(Uri("http://task.test")), Uri("http://task.test"), 0)

      expectMsg(Started)

      master ! new NewTasks(Seq(task.url))
      master ! new PullWork(1)
      expectMsg(new Work(Seq(task)))
      master ! new Result(task, Seq())

      expectMsg(Finished)
    }
    "store failures of in progress tasks" in {
      val master = TestActorRef(Props(classOf[MockMaster], config, self))
      expectMsg(Started)
      val task = new Task(Master.generateId(Uri("http://task.test")), Uri("http://task.test"), 0)
      master ! new NewTasks(Seq(task.url))
      master ! new PullWork(1)
      val exception = new Exception
      master ! new Error(task, exception)
      val status = master.underlyingActor.asInstanceOf[MockMaster].storage.get(task.id).get._2
      status should be(Master.WithError(exception))
    }
  }
}
