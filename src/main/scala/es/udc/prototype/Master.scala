package es.udc.prototype

import akka.actor.{ActorLogging, ActorRef, Actor}
import scala.collection.mutable.{Map => MMap}
import com.typesafe.config.Config
import spray.http.Uri
import es.udc.prototype.Master.TaskStatus

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:21
 */

object Master {

  sealed trait TaskStatus

  case object New extends TaskStatus

  case object InProgress extends TaskStatus

  case object Completed extends TaskStatus

  case class WithError(e: Throwable) extends TaskStatus

  def generateId(url: Uri): String = {
    url.toString()
  }
}

class Master(config: Config, listener: ActorRef) extends Actor with ActorLogging {
  protected val taskStorage: MMap[String, (Task, TaskStatus)] = MMap()

  import Master._

  protected var newTasks: Int = 0
  protected var completedTasks = 0

  override def preStart() {
    log.info("Sending Started message to the listener")
    listener ! Started
  }

  def getNewTasks(size: Int): Option[Seq[Task]] = {
    var newSize = size
    var tasks = Seq[Task]()
    if (size > newTasks)
      newSize = newTasks
    if (newSize > 0) {
      var tasksAdded = 0
      for ((id, (task, status)) <- taskStorage
           if status == New
           if tasksAdded < newSize) {
        taskStorage.put(task.id, (task, InProgress))
        newTasks -= 1
        tasks = task +: tasks
        tasksAdded += 1
      }
      Some(tasks)
    } else {
      None
    }
  }

  def storeCompleted(task: Task, status: TaskStatus) = {
    if (taskStorage.get(task.id) == Some((task, InProgress))) {
      taskStorage.put(task.id, (task, status))
      completedTasks += 1
      true
    } else {
      false
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
    if (storeCompleted(task, new WithError(reason))) {
      notifyIfCompleted()
    }
  }

  def addNewTasks(links: Seq[Uri], depth: Int) {
    links foreach {
      link =>
        if (!taskStorage.contains(Master.generateId(link))) {
          val id = Master.generateId(link)
          taskStorage.put(id, (new Task(id, link, depth), New))
          newTasks += 1
        }
    }
  }

  def receive = {
    case Result(task, links) =>
      log.info(s"Received Result from ${sender.path} of ${task.id}: $links")
      storeResult(task, links)
    case PullWork(size) =>
      getNewTasks(size) match {
        case Some(tasks) =>
          log.info(s"Received PullWork from manager ${sender.path}, sending ${tasks.size} tasks")
          sender ! Work(tasks)
        case None =>
          log.info(s"Received PullWork from manager ${sender.path}, no work to send")
      }
    case NewTasks(links) =>
      log.info(s"Received NewTasks from ${sender.path}: $links")
      addNewTasks(links, 0)
    case Error(task, reason: Throwable) =>
      log.info(s"Received Error of task: ${task.id} from ${sender.path}")
      storeError(task, reason)
  }
}
