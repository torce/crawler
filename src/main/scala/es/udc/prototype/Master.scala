package es.udc.prototype

import akka.actor.Actor
import scala.collection.mutable.{Map => MMap}
import es.udc.prototype.Master.TaskStatus.TaskStatus

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:21
 */

object Master {
  object TaskStatus extends Enumeration {
    type TaskStatus = Value
    val New, Completed = Value
  }
}

class Master extends Actor {
  private val taskStorage : MMap[String,TaskStatus] = MMap()
  import Master.TaskStatus._
  var newTasks : Int = 0
  def receive = {
    case PullWork(size) =>
      var tasks : Seq[Task] = Seq()
      var newSize : Int = size
      if(size > newTasks)
        newSize = newTasks
      if(newSize > 0) {
        var tasksAdded = 0
        for((task, status) <- taskStorage
            if status == New
            if tasksAdded < newSize) {
          taskStorage.remove(task)
          tasks = new Task(task) +: tasks
          tasksAdded = tasksAdded + 1
        }
        if(tasks.size > 0)
          sender ! new Work(tasks)
      }
    case Result(task, links) =>
      if(taskStorage.get(task) == Some(New)) {
        taskStorage.put(task, Completed)
        links foreach (link =>
          if(!taskStorage.contains(link)) {
            taskStorage.put(link, New)
            newTasks = newTasks + 1
          })
      }
    case NewTasks(links) =>
      links foreach { link =>
        taskStorage.put(link, New)
        newTasks = newTasks + 1
      }
  }
}
