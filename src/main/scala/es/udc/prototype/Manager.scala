package es.udc.prototype

import akka.actor._
import collection.mutable.{Queue => MQueue}
import scala.concurrent.duration._
import scala.language.postfixOps
import com.typesafe.config.Config
import akka.contrib.pattern.ClusterSingletonManager
import es.udc.prototype.util.SingletonProxy
import akka.actor.Status.Failure

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:18
 */
trait StartUp {
  this: Actor =>
  def initMaster(config: Config, listener: ActorRef): ActorRef = {
    val masterProps = Props(Class.forName(config.getString("prototype.master.class")), config, listener)
    context.actorOf(ClusterSingletonManager.props(
      singletonProps = _ => masterProps,
      singletonName = "master",
      terminationMessage = PoisonPill,
      role = None),
      name = "master-manager")
    context.actorOf(Props(classOf[SingletonProxy], Seq(self.path.name, "master-manager", "master")), "master-proxy")
  }

  def initDownloader(config: Config): ActorRef = {
    context.actorOf(Props(Class.forName(config.getString("prototype.downloader.class"))), "downloader")
  }

  def initCrawler(config: Config): ActorRef = {
    val extractor = Class.forName(config.getString("prototype.crawler.extractor.class")).newInstance()
    val crawlerProps = Props(
      Class.forName(config.getString("prototype.crawler.class")), extractor
    )
    context.actorOf(crawlerProps, "crawler")
  }
}

class Manager(config: Config, listener: ActorRef) extends Actor with ActorLogging with StartUp {

  case class NextTask(task : Task)
  val taskList = MQueue[Task]()

  val batchSize = config.getInt("prototype.manager.batch-size")
  val retryTimeout = config.getInt("prototype.manager.retry-timeout").milliseconds

  //Child actor references
  val master = initMaster(config, listener)
  val downloader = initDownloader(config)
  val crawler = initCrawler(config)

  override def preStart() {
    context.setReceiveTimeout(retryTimeout)
    log.info("Requesting work from Master")
    master ! new PullWork(batchSize)
  }

  def receive = {
    case Work(tasks) =>
      log.info(s"Work from Master received of size ${tasks.size}")
      tasks foreach { taskList.enqueue(_) }
      self ! NextTask(taskList.dequeue())
    case NextTask(task) =>
      downloader ! new Request(task, Map())
      if (taskList.isEmpty) {
        log.info("Task list empty. Requesting more work from Master")
        master ! new PullWork(batchSize)
      } else
        self ! NextTask(taskList.dequeue())
    case result : Result =>
      log.info(s"Forwarding result ${result.task.id} to Master")
      master ! result
    case response : Response =>
      log.info(s"Forwarding response ${response.task.id} to Crawler")
      crawler ! response
    case ReceiveTimeout if taskList.isEmpty =>
      log.warning("Receive timeout. Requesting more work from Master")
      master ! new PullWork(batchSize)
    case failure: Failure =>
      log.warning(s"Received Failure message: ${failure.cause}")
  }
}
