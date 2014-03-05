package es.udc.prototype

import akka.actor.{ReceiveTimeout, ActorRef, Actor}
import collection.mutable.{Queue => MQueue}
import scala.concurrent.duration._
import scala.language.postfixOps
import com.typesafe.config.Config

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:18
 */
class Manager(config: Config, master: ActorRef, downloader: ActorRef, crawler: ActorRef) extends Actor {

  case class NextTask(task : Task)
  val taskList = MQueue[Task]()

  var batchSize: Int = _

  override def preStart() {
    //Initialize from config
    batchSize = config.getInt("prototype.manager.batch-size")
    context.setReceiveTimeout(config.getInt("prototype.manager.retry-timeout").milliseconds)
    master ! new PullWork(batchSize)
  }

  def receive = {
    case Work(tasks) =>
      tasks foreach { taskList.enqueue(_) }
      self ! NextTask(taskList.dequeue())
    case NextTask(task) =>
      downloader ! new Request(task, Map())
      if(taskList.isEmpty)
        master ! new PullWork(batchSize)
      else
        self ! NextTask(taskList.dequeue())
    case result : Result =>
      master ! result
    case response : Response =>
      crawler ! response
    case ReceiveTimeout =>
      master ! new PullWork(batchSize)
  }
}
