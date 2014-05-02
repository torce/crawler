package es.udc.prototype.master

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.testkit.{TestActorRef, TestProbe, ImplicitSender, TestKit}
import org.scalatest.{WordSpecLike, BeforeAndAfterAll, Matchers}
import com.typesafe.config.{Config, ConfigFactory}
import spray.http.Uri
import scala.collection.mutable.{Map => MMap}
import es.udc.prototype._
import es.udc.prototype.NewTasks
import es.udc.prototype.Result
import es.udc.prototype.Work
import es.udc.prototype.PullWork
import es.udc.prototype.Error

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

  def initMaster(config: Config) = {
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
      val (master, _) = initMaster(config)
      val values = Set(Uri("http://test1.test"), Uri("http://test2.test"))
      master ! new NewTasks(values.toSeq)
      master ! new PullWork(2)
      expectMsgPF() {
        case Work(tasks) if tasks.map(_.url).forall(values.contains) => Unit
      }
    }
    "send only the number of tasks requested" in {
      val (master, _) = initMaster(config)
      val values = Seq(Uri("http://test1.test"), Uri("http://test2.test"))
      master ! new NewTasks(values)
      master ! new PullWork(1)
      expectMsgPF() {
        case Work(Seq(Task(_, url, 0))) if values.contains(url) => Unit
      }
    }
    "do not send work if there are not tasks to do" in {
      val (master, _) = initMaster(config)
      master ! PullWork(1)
      expectNoMsg()
    }
    "store links of a new task and set it as completed" in {
      val (master, _) = initMaster(config)
      val completedTask = Uri("http://test1.test")
      val childTasks = Set(Uri("http://test2.test"), Uri("http://test3.test"))
      master ! new NewTasks(Seq(completedTask))
      master ! new PullWork(1)
      expectMsgPF() {
        case Work(Seq(Task(_, `completedTask`, 0))) => Unit
      }
      master ! new Result(new DefaultTask(Master.generateId(completedTask), completedTask, 0), childTasks.toSeq)
      master ! new PullWork(2)

      //Check that the Uris and depth are the expected
      expectMsgPF() {
        case Work(tasks) if tasks.map(_.url).forall(childTasks.contains) && tasks.forall(_.depth == 1) => Unit
      }
    }
    "not store links of unknown task" in {
      val (master, _) = initMaster(config)
      val unknownTask = Uri("http://unknown.test")
      val firstTask = new DefaultTask(Master.generateId(Uri("http://task.test")), Uri("http://task.test"), 0)
      val values = Seq(Uri("http://test2.test"), Uri("http://test3.test"))
      master ! new NewTasks(Seq(firstTask.url))
      master ! new Result(new DefaultTask(Master.generateId(unknownTask), unknownTask, 0), values)
      master ! new PullWork(2)
      expectMsgPF() {
        case Work(Seq(`firstTask`)) => Unit
      }
      master ! new PullWork(2)
      master ! new Result(firstTask, Seq())
      expectNoMsg()
    }
    "send a Finished message to listener when all the tasks are done" in {
      val master = system.actorOf(Props(classOf[Master], config, self))
      val task = new DefaultTask(Master.generateId(Uri("http://task.test")), Uri("http://task.test"), 0)

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
      val task = new DefaultTask(Master.generateId(Uri("http://task.test")), Uri("http://task.test"), 0)
      master ! new NewTasks(Seq(task.url))
      master ! new PullWork(1)
      val exception = new Exception
      master ! new Error(task, exception)
      val status = master.underlyingActor.asInstanceOf[MockMaster].storage.get(task.id).get._2
      status should be(Master.WithError(exception.toString))
    }
    "restart running tasks after a timeout" in {
      val (master, _) = initMaster(config)
      val probe = TestProbe()
      val task = new DefaultTask(Master.generateId(Uri("http://task.test")), Uri("http://task.test"), 0)
      probe.send(master, new NewTasks(Seq(task.url)))
      probe.send(master, new PullWork(1))
      probe.expectMsg(new Work(Seq(task)))
      Thread.sleep(config.getInt("prototype.master.retry-timeout"))
      probe.send(master, new PullWork(1))
      probe.expectMsg(new Work(Seq(task)))
    }
  }
}
