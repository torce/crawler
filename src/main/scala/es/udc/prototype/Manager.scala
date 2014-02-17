package es.udc.prototype

import akka.actor.{ReceiveTimeout, ActorRef, Actor}
import collection.mutable.Queue
import scala.concurrent.duration._

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:18
 */
object Manager {
  val BATCH_SIZE = 5
  val RETRY_TIMEOUT = 500 milliseconds
}

class Manager(master : ActorRef, downloader : ActorRef, crawler : ActorRef) extends Actor {
  case class NextTask(task : Task)
  val taskList = Queue[Task]()

  override def preStart {
    master ! new PullWork(Manager.BATCH_SIZE)
    context.setReceiveTimeout(Manager.RETRY_TIMEOUT)
  }

  def receive = {
    case Work(tasks) =>
      tasks foreach { taskList.enqueue(_) }
      self ! NextTask(taskList.dequeue())
    case NextTask(task) =>
      //TODO id will be different from url
      downloader ! new Request(task.id, task.id)
      if(taskList.isEmpty)
        master ! new PullWork(Manager.BATCH_SIZE)
      else
        self ! NextTask(taskList.dequeue())
    case result : Result =>
      master forward result
    case response : Response =>
      crawler forward response
    case ReceiveTimeout =>
      master ! new PullWork(Manager.BATCH_SIZE)
  }
}
