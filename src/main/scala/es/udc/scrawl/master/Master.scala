package es.udc.scrawl.master

import akka.actor.{ActorLogging, ActorRef, Actor}
import scala.collection.mutable.{Map => MMap}
import com.typesafe.config.Config
import spray.http.Uri
import es.udc.scrawl._
import scala.Some
import es.udc.scrawl.NewTasks
import es.udc.scrawl.Result
import es.udc.scrawl.Work
import es.udc.scrawl.PullWork
import es.udc.scrawl.Task
import es.udc.scrawl.master.Master.TaskStatus

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:21
 */

object Master {

  sealed trait TaskStatus

  case object New extends TaskStatus

  case class InProgress(started: Long = System.currentTimeMillis()) extends TaskStatus

  case object Completed extends TaskStatus

  case class WithError(e: String) extends TaskStatus

  def generateId(url: Uri): String = {
    url.toString()
  }
}

case class DefaultTask(id: String, url: Uri, depth: Int) extends Task

class Master(config: Config, listener: ActorRef) extends Actor with ActorLogging {
  protected val taskStorage: MMap[String, (Task, TaskStatus)] = MMap()

  import Master._

  protected var newTasks: Int = 0
  protected var completedTasks = 0

  val retryTimeout = config.getInt("scrawl.master.retry-timeout")

  override def preStart() {
    log.info("Sending Started message to the listener")
    listener ! Started
  }

  def getTasks(size: Int): Seq[Task] = {
    var tasks = Seq[Task]()
    if (size > 0) {
      var tasksAdded = 0
      for ((id, (task, status)) <- taskStorage
           if status == New || (status.isInstanceOf[InProgress] && (status.asInstanceOf[InProgress].started < (System.currentTimeMillis() - retryTimeout)))
           if tasksAdded < size) {
        taskStorage.put(id, (task, new InProgress(System.currentTimeMillis())))
        newTasks -= 1
        tasks = task +: tasks
        tasksAdded += 1
      }
      tasks
    } else {
      Seq()
    }
  }

  def storeCompleted(task: Task, status: TaskStatus) = {
    taskStorage.get(task.id) match {
      case Some((`task`, InProgress(_))) =>
        taskStorage.put(task.id, (task, status))
        completedTasks += 1
        true
      case _ => false
    }
  }

  def notifyIfCompleted() {
    if (completedTasks == taskStorage.size) {
      log.info("Sending Finished message to the listener")
      listener ! Finished
    }
  }

  def storeResult(task: Task, links: Seq[Uri]): Unit = {
    if (storeCompleted(task, Completed)) {
      addNewTasks(links, task.depth + 1)
      notifyIfCompleted()
    }
  }

  def storeError(task: Task, reason: Throwable) {
    if (storeCompleted(task, new WithError(reason.toString))) {
      notifyIfCompleted()
    }
  }

  def addNewTasks(links: Seq[Uri], depth: Int) {
    links foreach {
      link =>
        if (!taskStorage.contains(Master.generateId(link))) {
          val id = Master.generateId(link)
          taskStorage.put(id, (new DefaultTask(id, link, depth), New))
          newTasks += 1
        }
    }
  }

  def receive = {
    case Result(task, links) =>
      log.info(s"Received Result from ${sender.path} of ${task.id}: $links")
      storeResult(task, links)
    case PullWork(size) =>
      val tasks = getTasks(size)
      if (tasks.isEmpty) {
        log.info(s"Received PullWork from manager ${sender.path}, no work to send")
      } else {
        log.info(s"Received PullWork from manager ${sender.path}, sending ${tasks.size} tasks")
        sender ! Work(tasks)
      }
    case NewTasks(links) =>
      log.info(s"Received NewTasks from ${sender.path}: $links")
      addNewTasks(links, 0)
    case Error(task, reason: Throwable) =>
      log.info(s"Received Error of task: ${task.id} from ${sender.path}")
      storeError(task, reason)
  }
}
