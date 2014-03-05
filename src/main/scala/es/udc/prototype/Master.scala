package es.udc.prototype

import akka.actor.{ActorRef, Actor}
import scala.collection.mutable.{Map => MMap}
import es.udc.prototype.Master.TaskStatus.TaskStatus
import com.typesafe.config.Config

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:21
 */

object Master {
  object TaskStatus extends Enumeration {
    type TaskStatus = Value
    val New, InProgress, Completed = Value
  }

  def generateId(url: String) = {
    url
  }
}

class Master(config: Config, listener: ActorRef) extends Actor {
  private val taskStorage: MMap[String, (Task, TaskStatus)] = MMap()

  import Master.TaskStatus._
  var newTasks : Int = 0
  var completedTasks = 0

  override def preStart() {
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

  def storeResult(task: Task, links: Seq[String]): Unit = {
    if (taskStorage.get(task.id) == Some((task, InProgress))) {
      taskStorage.put(task.id, (task, Completed))
      completedTasks += 1
      addNewTasks(links)
      if (completedTasks == taskStorage.size)
        listener ! Finished
    }
  }

  def addNewTasks(links: Seq[String]) {
    links foreach {
      link =>
        if (!taskStorage.contains(link)) {
          val id = Master.generateId(link)
          taskStorage.put(id, (new Task(id, link), New))
          newTasks += 1
        }
    }
  }

  def receive = {
    case Result(task, links) =>
      storeResult(task, links)
    case PullWork(size) =>
      getNewTasks(size) match {
        case Some(tasks) =>
          sender ! Work(tasks)
        case _ =>
      }
    case NewTasks(links) =>
      addNewTasks(links)
  }
}
