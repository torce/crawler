package es.udc.prototype.master

import com.typesafe.config.{ConfigException, Config}
import es.udc.prototype._
import akka.actor.{Actor, ActorLogging, ActorRef}
import spray.http.Uri
import es.udc.prototype.master.Master._
import es.udc.prototype.Task
import sprouch._
import es.udc.prototype.master.Master.InProgress
import scala.Some
import es.udc.prototype.NewTasks
import es.udc.prototype.Result
import es.udc.prototype.Work
import scala.util.Success
import es.udc.prototype.PullWork
import es.udc.prototype.master.Master.WithError
import es.udc.prototype.master.MasterCouch.RevedCouchTask
import scala.concurrent.{Await, Future}
import CouchTaskJsonProtocol._
import scala.util.Failure
import scala.concurrent.duration._
import scala.language.implicitConversions

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

  /*  class SerializableRevedDocument[A](id: String, rev: String, data: A, attachments: Map[String, AttachmentStub])
      extends RevedDocument[A](id, rev, data, attachments) with Serializable

    implicit def RevedDocument2Serializable[A](rd: RevedDocument[A]): SerializableRevedDocument[A] = {
      new SerializableRevedDocument[A](rd.id, rd.rev, rd.data, rd.attachments)
    }

    implicit def Serializable2RevedDocument[A](srd: SerializableRevedDocument[A]): RevedDocument[A] = {
      new RevedDocument[A](srd.id, srd.rev, srd.data, srd.attachments)
    }
  */
  /*case class RevedCouchTask(_rev: RevedDocument[NewCouchTask]) extends Task {
    def id = _rev.id

    def url = _rev.data.url

    def depth = _rev.data.depth

    def rev = _rev.rev

    def status = _rev.data.status

    def revDoc = _rev
  }*/
  case class RevedCouchTask(id: String, rev: String, data: NewCouchTask) extends Task {
    def this(rd: RevedDocument[NewCouchTask]) = this(rd.id, rd.rev, rd.data)

    def url = data.url

    def status = data.status

    def depth = data.depth

    def revDoc = new RevedDocument(id, rev, data, Map())
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

  implicit val queryTimeout = config.getInt("prototype.master.couch.query-timeout").milliseconds

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
    couch.createDb(config.getString("prototype.master.couch.db-name")) recoverWith {
      case _ => couch.getDb(config.getString("prototype.master.couch.db-name"))
    }
  else
    couch.getDb(config.getString("prototype.master.couch.db-name"))

  if (createDb) {
    Some(NewDocument("crawler",
      Views(Map("new-tasks" -> newTasks,
        "started" -> started,
        "remaining" -> remaining))).createViews)
  }

  override def preStart() {
    log.info("Sending Started message to the listener")
    log.info(s"${self.path}")
    listener ! Started
  }

  def storeResult(task: RevedCouchTask, links: Seq[Uri]) {
    (task.revDoc := task.revDoc.data.copy(status = Completed)) recoverWith {
      //Resolve update conflicts, overwrite status if it is New or WithError
      case SprouchException(ErrorResponse(409, _)) =>
        for {
          updatedDoc <- task.revDoc.get
        } yield {
          if(updatedDoc.data.status != Completed) {
            updatedDoc := task.revDoc.data.copy(status = Completed)
          } else {
            Future.successful(updatedDoc)
          }
        }
      case e =>
        log.warning(s"Error storing task completed with error with id: ${task.id} due to $e")
        Future.failed(e)
    }
    addNewTasks(links, task.depth + 1)
  }

  def storeError(task: RevedCouchTask, reason: Throwable) {
    (task.revDoc := task.revDoc.data.copy(status = new WithError(reason.toString))) recoverWith {
      //Resolve update conflicts, overwrite status only if it is New
      case SprouchException(ErrorResponse(409, _)) =>
        for {
          updatedDoc <- task.revDoc.get
        } yield {
          if(!updatedDoc.data.status.isInstanceOf[WithError] && updatedDoc.data.status != Completed) {
            updatedDoc := task.revDoc.data.copy(status = new WithError(reason.toString))
          }
        }
      case e =>
        log.warning(s"Error storing task completed with error with id: ${task.id} due to $e")
        Future.failed(e)
    }
  }

  def addNewTasks(links: Seq[Uri], depth: Int) {
    val docs = links.map {
      link =>
        NewDocument(encodeId(link),
          new NewCouchTask(link, depth, New), Map())
    }
    db.flatMap(_.bulkPutWithError(docs)).onComplete {
      case Success(result) =>
        if(!result._2.isEmpty) {
          log.debug(s"Error storing new tasks, already exists: ${result._2.map(_.id)}")
        }
        notifyIfCompleted()
      case Failure(e@SprouchException(ErrorResponse(code, Some(ErrorResponseBody(error, reason))))) =>
        log.warning(s"$e: $code $error $reason")
      case Failure(e) =>
        log.error(s"$e: ${e.getMessage}")
    }
  }

  def conflictSolver(conflict: RevedCouchTask): PartialFunction[Throwable, Future[Option[RevedCouchTask]]] = {
    case SprouchException(ErrorResponse(409, _)) =>
      conflict.revDoc.get.flatMap {
        updatedDoc =>
          updatedDoc.data.status match {
            case s if s == New || s.isInstanceOf[InProgress] =>
              (updatedDoc := conflict.revDoc.data.copy(status = new InProgress())).map(t => Some(new RevedCouchTask(t)))
            case s if s == Completed || s.isInstanceOf[WithError] =>
              Future(None)
          }
      }
  }

  def getTasks(size: Int): Future[Seq[RevedCouchTask]] = {
    def getNewTasks(size: Int): Future[Seq[RevedCouchTask]] =
      getTaskWitQuery(queryView[String, Null]("crawler", "new-tasks", limit = Some(size),
        flags = ViewQueryFlag(reduce = false, include_docs = true)))

    def getTasksToRetry(size: Int): Future[Seq[RevedCouchTask]] = if(size > 0) {
      getTaskWitQuery(queryView[Double, Null]("crawler", "started", limit = Some(size),
        startKey = Some(System.currentTimeMillis() - retryTimeout),
        flags = ViewQueryFlag(reduce = false, include_docs = true, descending = true)))
    } else {
      Future(Seq())
    }

    for {
      tasks <- getNewTasks(size)
      tasksToRetry <- getTasksToRetry(size - tasks.size)
    } yield {
      tasks ++ tasksToRetry
    }
  }

  def getTaskWitQuery(query: Future[ViewResponse[_,_]]): Future[Seq[RevedCouchTask]] = {
    for {
      results <- query
      docs <- Future(
        results.rows.flatMap(
          _.docAs[RevedCouchTask]))
      updatedTasks <- Future(docs.map(task =>
        (task.revDoc := task.revDoc.data.copy(status = new InProgress()))
          .map(t => Some(new RevedCouchTask(t))).recoverWith(conflictSolver(task))))
      tasksToReturn <- Future.sequence(updatedTasks)
    } yield {
      tasksToReturn.flatten
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
      log.debug(s"Received Result from ${sender.path} of ${task.id}")
      storeResult(task, links)
    case PullWork(size) =>
      val currentSender = sender
      getTasks(size).onComplete {
        case Success(tasks) =>
          if (tasks.isEmpty) {
            log.debug(s"Received PullWork from manager ${currentSender.path}, no work to send")
            notifyIfCompleted()
          } else {
            log.debug(s"Received PullWork from manager ${currentSender.path}, sending ${tasks.size} tasks")
            currentSender ! Work(tasks)
          }
        case Failure(e@SprouchException(ErrorResponse(code, Some(ErrorResponseBody(error, reason))))) =>
          log.warning(s"$e: $code $error $reason")
        case Failure(e) =>
          log.warning(s"$e: ${e.getMessage}")
      }

    case NewTasks(links) =>
      log.debug(s"Received NewTasks from ${sender.path}: $links")
      addNewTasks(links, 0)
    case Error(task: RevedCouchTask, reason: Throwable) =>
      log.debug(s"Received Error of task: ${task.id} from ${sender.path}")
      storeError(task, reason)
      notifyIfCompleted()
  }
}
