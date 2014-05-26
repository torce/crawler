package es.udc.scrawl.master

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, Matchers, WordSpecLike}
import es.udc.scrawl._
import spray.http.Uri
import es.udc.scrawl.NewTasks
import es.udc.scrawl.Result
import es.udc.scrawl.Work
import es.udc.scrawl.PullWork
import scala.concurrent.duration._
import scala.concurrent.Await
import sprouch._, dsl._
import scala.util.Success
import scala.concurrent.Future
import es.udc.scrawl.master.MasterCouch.RevedCouchTask
import es.udc.scrawl.master.Master.WithError

class MasterCouchTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterEach
with BeforeAndAfterAll {

  val config = ConfigFactory.load("application.test.conf")

  val storeTimeout = 5000
  val msgTimeout = 10.seconds

  val sprouchConfig = sprouch.Config(system)
  val couch = Couch(sprouchConfig)

  implicit var db: Future[Database] = _

  import system.dispatcher

  override def afterAll() {
    system.shutdown()
  }

  override def beforeEach() {
    import sprouch.JsonProtocol._
    val dbName = config.getString("scrawl.master.couch.db-name")
    db = couch.createDb(dbName) recoverWith {
      case _ => for {
        _ <- couch.deleteDb(dbName)
        database <- couch.createDb(dbName)
      } yield database
    }

    val newTasks = MapReduce(map =
      """function(doc) {
           if(doc.status.status && doc.status.status === 'New') {
             emit(doc._id);
           }
         }
      """.stripMargin)

    val started = MapReduce(map =
      """function(doc) {
           if(doc.status.status && doc.status.status === 'InProgress') {
             emit(doc.status.started)
           }
         }""".stripMargin)

    val remaining = MapReduce(map =
      """function(doc) {
         if(doc.status.status && ((doc.status.status === 'New') || (doc.status.status === 'InProgress'))) {
           emit(null, 1);
         } else {
           emit(null, 0);
         }
       }""",
      reduce =
        """function(keys, values, rereduce) {
         return sum(values);
       }""".stripMargin)

    val errors = MapReduce(map =
      """function(doc) {
           if(doc.status.status && doc.status.status === 'WithError') {
             emit(doc._id)
           }
      }""".stripMargin)

    Await.result(NewDocument("crawler",
      Views(Map("new-tasks" -> newTasks,
        "started" -> started,
        "remaining" -> remaining,
        "errors" -> errors))).createViews, 10.seconds)
  }

  def initMaster(config: Config) = {
    val listener = TestProbe()
    val master = system.actorOf(Props(classOf[MasterCouch], config, listener.ref))
    //listener.expectMsg(Started)
    (master, listener)
  }

  "A Master actor" should {
    "send a Started message to listener after start" in {
      system.actorOf(Props(classOf[MasterCouch], config, self))
      expectMsg(msgTimeout, Started)
    }
    "store new tasks" in {
      val (master, _) = initMaster(config)
      val values = Set(Uri("http://test1.test"), Uri("http://test2.test"))
      master ! new NewTasks(values.toSeq)

      Thread.sleep(storeTimeout)

      master ! new PullWork(2)

      expectMsgPF(msgTimeout) {
        case Work(tasks) if tasks.map(_.url).forall(values.contains) => Unit
      }
    }
    "send only the number of tasks requested" in {
      val (master, _) = initMaster(config)
      val values = Seq(Uri("http://test1.test"), Uri("http://test2.test"))
      master ! new NewTasks(values)

      Thread.sleep(storeTimeout)

      master ! new PullWork(1)
      expectMsgPF(msgTimeout) {
        case Work(Seq(Task(_, url, 0))) if values.contains(url) => Unit
      }
    }
    "do not send work if there are not tasks to do" in {
      val (master, _) = initMaster(config)
      master ! PullWork(1)
      expectNoMsg(msgTimeout)
    }
    "store links of a new task and set it as completed" in {
      val (master, _) = initMaster(config)
      val completedTask = Uri("http://test1.test")
      val childTasks = Set(Uri("http://test2.test"), Uri("http://test3.test"))
      master ! new NewTasks(Seq(completedTask))

      Thread.sleep(storeTimeout)

      master ! new PullWork(1)

      val task = expectMsgPF(msgTimeout) {
        case Work(Seq(task@Task(_, `completedTask`, 0))) => task
      }
      master ! new Result(task, childTasks.toSeq)

      Thread.sleep(storeTimeout)

      master ! new PullWork(2)

      //Check that the Uris and depth are the expected
      expectMsgPF(msgTimeout) {
        case Work(tasks) if tasks.map(_.url).forall(childTasks.contains) && tasks.forall(_.depth == 1) => Unit
      }
    }
    "send a Finished message to listener when all the tasks are done" in {
      val (master, listener) = initMaster(config)
      val url = Uri("http://task.test")

      listener.expectMsg(msgTimeout, Started)

      master ! new NewTasks(Seq(url))

      Thread.sleep(storeTimeout)

      master ! new PullWork(1)
      val task = expectMsgPF(msgTimeout) {
        case Work(Seq(task@Task(_, `url`, 0))) => task
      }

      master ! new Result(task, Seq())

      listener.expectMsg(msgTimeout * 2, Finished)
    }
    "store failures of in progress tasks" in {
      import CouchTaskJsonProtocol._

      val (master, _) = initMaster(config)
      val url = Uri("http://task.test")
      master ! new NewTasks(Seq(url))

      Thread.sleep(storeTimeout)

      master ! new PullWork(1)
      val task = expectMsgPF(msgTimeout) {
        case Work(Seq(task@Task(_, `url`, 0))) => task
      }

      master ! new Error(task, new Exception)

      val query = queryView[String, Null]("crawler", "errors",
        flags = ViewQueryFlag(reduce = false, include_docs = true))

      val docs = query.map(_.rows.flatMap(_.docAs[RevedCouchTask]))

      docs.map {
        list =>
          list.size should be(1)
          list(0).status should be(WithError(new Exception().toString))
      }

      Await.result(docs, msgTimeout)
    }
    "restart running tasks after a timeout if there are not new tasks waiting" in {
      val (master, _) = initMaster(config)
      val probe = TestProbe()
      val url = Uri("http://task.test")
      probe.send(master, new NewTasks(Seq(url)))

      Thread.sleep(storeTimeout)

      probe.send(master, new PullWork(1))
      val task = probe.expectMsgPF(msgTimeout) {
        case Work(Seq(task@Task(_, `url`, 0))) => task
      }

      Thread.sleep(config.getInt("scrawl.master.retry-timeout"))

      probe.send(master, new PullWork(1))
      probe.expectMsgPF(msgTimeout) {
        case Work(Seq(Task(id, turl, 0))) if id == task.id && turl == task.url => Unit
      }
    }
  }
}
