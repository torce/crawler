package es.udc.prototype.master

import com.typesafe.config.{ConfigException, Config}
import es.udc.prototype._
import akka.actor.{Actor, ActorLogging, ActorRef}
import spray.http.Uri
import es.udc.prototype.master.Master._
import es.udc.prototype.Task
import sprouch._
import scala.util.Failure
import es.udc.prototype.master.Master.InProgress
import scala.Some
import es.udc.prototype.NewTasks
import es.udc.prototype.Result
import es.udc.prototype.Work
import scala.util.Success
import es.udc.prototype.PullWork
import es.udc.prototype.master.Master.WithError
import es.udc.prototype.master.MasterCouch.RevedCouchTask
import scala.concurrent.Future
import CouchTaskJsonProtocol._
import spray.json.JsNumber

trait MasterCouchViews {
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
}

object MasterCouch {

  class RevedCouchTask(_rev: RevedDocument[NewCouchTask]) extends Task {
    def id = _rev.id

    def url = _rev.data.url

    def depth = _rev.data.depth

    def rev = _rev.rev

    def status = _rev.data.status

    def revDoc = _rev
  }

  case class NewCouchTask(url: Uri, depth: Int, status: TaskStatus)

}

class MasterCouch(config: Config, listener: ActorRef) extends Actor with ActorLogging with MasterCouchViews {

  import MasterCouch.NewCouchTask
  import MasterCouch.RevedCouchTask
  import sprouch._
  import sprouch.JsonProtocol._
  import sprouch.dsl._
  import context.dispatcher


  val retryTimeout = config.getInt("prototype.master.retry-timeout")

  val userPass = try {
    Some((config.getString("prototype.master.couch.user"), config.getString("prototype.master.couch.pass")))
  } catch {
    case e: ConfigException.Missing => None
  }


  val sprouchConfig = sprouch.Config(
    context.system,
    hostName = config.getString("prototype.master.couch.hostname"),
    port = config.getInt("prototype.master.couch.port"),
    userPass = userPass
  )

  val couch = Couch(sprouchConfig)
  val createDb = config.getBoolean("prototype.master.couch.create-db")

  implicit val db = if (createDb)
    couch.createDb(config.getString("prototype.master.couch.db-name"))
  else
    couch.getDb(config.getString("prototype.master.couch.db-name"))

  if (createDb) {
    NewDocument("crawler",
      Views(Map("new-tasks" -> newTasks,
        "started" -> started,
        "remaining" -> remaining)).createViews)
  }

  def storeResult(task: RevedCouchTask, links: Seq[Uri]) {
    (task.revDoc := task.revDoc.data.copy(status = Completed)).onFailure {
      case _ =>
        log.warning(s"Error storing completed task with id: ${task.id}")
    } //Update the status
    addNewTasks(links, task.depth + 1)
  }

  def storeError(task: RevedCouchTask, reason: Throwable) {
    (task.revDoc := task.revDoc.data.copy(status = new WithError(reason.toString))).onFailure {
      case _ => log.warning(s"Error storing task completed with error with id: ${task.id}")
    }
  }

  def addNewTasks(links: Seq[Uri], depth: Int) {
    links.foreach {
      link =>
        new NewCouchTask(link, depth, New).create.onFailure {
          case e => log.warning(s"Error storing new task with url: $link")
        }
    }
  }

  def getNewTasks(size: Int): Future[Seq[RevedCouchTask]] = {
    // Query the view
    val query = queryView[String, Null]("crawler", "new-tasks", limit = Some(size),
      flags = ViewQueryFlag(reduce = false, include_docs = true))

    // Deserializer
    val docs: Future[Seq[RevedCouchTask]] = query.map(_.rows.flatMap(_.docAs[RevedCouchTask]))

    // Store with status InProgress and timestamp, get the new rev

    docs.flatMap {
      seq =>
        val updatedTasks = seq.map {
          task =>
            (task.revDoc := task.revDoc.data.copy(status = new InProgress())).map(new RevedCouchTask(_))
        }
        Future.sequence(updatedTasks)
    }
  }

  def getTasksToRetry(size: Int): Future[Seq[RevedCouchTask]] = {
    import sprouch._, dsl._, JsonProtocol._
    if (size > 0) {
      val query = queryView[Double, Null]("crawler", "started", limit = Some(size),
        startKey = Some(System.currentTimeMillis() - retryTimeout),
        flags = ViewQueryFlag(reduce = false, include_docs = true, descending = true))

      val docs = query.map(_.rows.flatMap(_.docAs[RevedCouchTask]))

      val updateStatus = docs.map {
        list =>
          list.foreach(doc => doc.revDoc := doc.revDoc.data.copy(status = new InProgress()))
          list
      }
      updateStatus
    } else {
      Future(Seq())
    }
  }

  def notifyIfCompleted() {
    val remaining = queryView[Null, Double]("crawler", "remaining")
    remaining.onFailure {
      case _ => log.info("Fail checking remaining tasks")
    }
    remaining.map {
      n =>
        if (n.values.head == 0) {
          log.info("Sending Finished message to the listener")
          listener ! Finished
        }
    }
  }

  def receive = {
    case Result(task: RevedCouchTask, links) =>
      log.info(s"Received Result from ${sender.path} of ${task.id}: $links")
      storeResult(task, links)
      notifyIfCompleted()
    case PullWork(size) =>
      val currentSender = sender

      val f: Future[Seq[RevedCouchTask]] = for {
        tasks <- getNewTasks(size)
        toRetry <- getTasksToRetry(size - tasks.size)
      } yield {
        tasks ++ toRetry
      }

      f.onComplete {
        case Success(tasks) =>
          if (tasks.isEmpty) {
            log.info(s"Received PullWork from manager ${currentSender.path}, no work to send")
            notifyIfCompleted()
          } else {
            log.info(s"Received PullWork from manager ${currentSender.path}, sending ${tasks.size} tasks")
            currentSender ! Work(tasks)
          }
        case Failure(e) => log.error(s"$e")
      }

    case NewTasks(links) =>
      log.info(s"Received NewTasks from ${sender.path}: $links")
      addNewTasks(links, 0)
    case Error(task: RevedCouchTask, reason: Throwable) =>
      log.info(s"Received Error of task: ${task.id} from ${sender.path}")
      storeError(task, reason)
      notifyIfCompleted()
  }
}
