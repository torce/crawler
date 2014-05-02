package es.udc.prototype

import akka.actor._
import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.language.postfixOps
import com.typesafe.config.Config
import akka.contrib.pattern.ClusterSingletonManager
import es.udc.prototype.util.SingletonProxy
import es.udc.prototype.pipeline._
import es.udc.prototype.pipeline.ToRight
import es.udc.prototype.pipeline.ToLeft
import akka.routing.FromConfig

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
    context.actorOf(Props(Class.forName(config.getString("prototype.crawler.class")), config).withRouter(FromConfig()), "crawler")
  }

  def initRequestPipeline(config: Config) = {
    context.actorOf(Props(classOf[Pipeline], config), "request-pipeline")
  }

  def initResultPipeline(config: Config) = {
    context.actorOf(Props(classOf[Pipeline], config), "result-pipeline")
  }
}

sealed trait ManagerState

case object Created extends ManagerState

case object RequestPipelineActive extends ManagerState

case object ResultPipelineActive extends ManagerState

case object Active extends ManagerState

case class ManagerData(taskList: Queue[Task],
                       downloaderQueue: Queue[Response] = Queue(),
                       requestPipeQueue: Queue[Response] = Queue(),
                       crawlerQueue: Queue[Result] = Queue())

class Manager(config: Config, listener: ActorRef) extends Actor
with ActorLogging
with StartUp
with FSM[ManagerState, ManagerData] {

  case class NextTask(task: Task)

  val batchSize = config.getInt("prototype.manager.batch-size")
  val retryTimeout = config.getInt("prototype.manager.retry-timeout").milliseconds

  //Child actor references
  val master = initMaster(config, listener)
  val downloader = initDownloader(config)
  val crawler = initCrawler(config)
  val requestPipeline = initRequestPipeline(config)
  val resultPipeline = initResultPipeline(config)

  override def preStart() {
    log.info("Requesting work from Master")
    master ! new PullWork(batchSize)
  }

  startWith(Created, ManagerData(Queue()))

  val fromMaster: StateFunction = {
    case Event(Work(tasks), ManagerData(Queue(), _, _, _)) =>
      val (task, remaining) = Queue(tasks: _*).dequeue
      self ! new NextTask(task)
      stay using new ManagerData(remaining)

    case Event(Work(tasks), ManagerData(taskList, _, _, _)) =>
      stay using new ManagerData(taskList.enqueue(collection.immutable.Seq(tasks: _*)))
  }

  val fromSelf: StateFunction = {
    case Event(NextTask(task), ManagerData(Queue(), _, _, _)) =>
      log.info("Task list empty. Requesting more work from Master")
      requestPipeline ! new ToRight(new Request(task, Map()))
      master ! PullWork(batchSize)
      stay using new ManagerData(Queue())

    case Event(NextTask(task), ManagerData(tasks, _, _, _)) =>
      requestPipeline ! new ToRight(new Request(task, Map()))
      val (nextTask, remaining) = tasks.dequeue
      self ! new NextTask(nextTask)
      stay using new ManagerData(remaining)
  }

  val fromDownloader: StateFunction = {
    case Event(response: Response, _) if sender == downloader =>
      log.info(s"Forwarding response ${response.task.id} to RequestPipeline")
      requestPipeline ! new ToLeft(response)
      stay()
  }

  val fromRequestPipeline: StateFunction = {
    case Event(request: Request, _) if sender == requestPipeline =>
      log.info(s"Forwarding request ${request.task.id} to Downloader")
      downloader ! request
      stay()

    case Event(response: Response, _) if sender == requestPipeline =>
      log.info(s"Forwarding response ${response.task.id} to ResultPipeline")
      resultPipeline ! new ToRight(response)
      stay()
  }

  val fromResultPipeline: StateFunction = {
    case Event(response: Response, _) if sender == resultPipeline =>
      log.info(s"Forwarding response ${response.task.id} to Crawler")
      crawler ! response
      stay()

    case Event(result: Result, _) if sender == resultPipeline =>
      log.info(s"Forwarding result ${result.task.id} to Master")
      master ! result
      stay()
  }

  val fromCrawler: StateFunction = {
    case Event(result: Result, _) if sender == crawler =>
      log.info(s"Forwarding result ${result.task.id} to ResultPipeline")
      resultPipeline ! new ToLeft(result)
      stay()
  }

  val errorHandler: StateFunction = {
    case Event(error: Error, _) =>
      log.info(s"Forwarding error of task ${error.task.id} to Master")
      master ! error
      stay()
  }

  when(Created) {
    case Event(PipelineStarted, ManagerData(taskList, downloaderQueue, rpq, cq)) if sender == requestPipeline =>
      downloaderQueue.foreach(requestPipeline ! new ToLeft(_))
      if (taskList.isEmpty) {
        goto(RequestPipelineActive)
      } else {
        val (task, remaining) = taskList.dequeue
        self ! new NextTask(task)
        goto(RequestPipelineActive) using new ManagerData(remaining, downloaderQueue, rpq, cq)
      }

    case Event(PipelineStarted, ManagerData(taskList, dq, rpq, crawlerQueue)) if sender == resultPipeline =>
      crawlerQueue.foreach(resultPipeline ! new ToLeft(_))
      goto(ResultPipelineActive)

    case Event(Work(tasks), ManagerData(taskList, _, _, _)) =>
      stay using new ManagerData(taskList.enqueue(collection.immutable.Seq(tasks: _*)))

    case Event(result: Result, ManagerData(tl, dq, rpq, crawlerQueue)) if sender == crawler =>
      log.info(s"Storing result ${result.task.id} from Crawler to send at ResultPipeline later")
      stay using new ManagerData(tl, dq, rpq, crawlerQueue.enqueue(result))

    case Event(response: Response, ManagerData(tl, downloaderQueue, rpq, cq)) if sender == downloader =>
      log.info(s"Storing response ${response.task.id} from Downloader to send at RequestPipeline later")
      stay using new ManagerData(tl, downloaderQueue.enqueue(response), rpq, cq)
  }

  when(RequestPipelineActive)(fromMaster orElse fromSelf orElse fromDownloader orElse errorHandler orElse {
    case Event(PipelineStarted, ManagerData(taskList, _, requestPipeQueue, crawlerQueue)) if sender == resultPipeline =>
      requestPipeQueue.foreach(resultPipeline ! new ToRight(_))
      crawlerQueue.foreach(resultPipeline ! new ToLeft(_))

      if (taskList.isEmpty) {
        goto(Active)
      } else {
        val (task, remaining) = taskList.dequeue
        self ! new NextTask(task)
        goto(Active) using new ManagerData(remaining)
      }

    case Event(PipelineRestarting, _) if sender == requestPipeline =>
      goto(Created)

    case Event(request: Request, _) if sender == requestPipeline =>
      log.info(s"Forwarding request ${request.task.id} to Downloader")
      downloader ! request
      stay()

    case Event(response: Response, ManagerData(tl, dq, requestPipeQueue, cq)) if sender == requestPipeline =>
      log.info(s"Storing response ${response.task.id} from RequestPipeline to send at ResultPipeline later")
      stay using new ManagerData(tl, dq, requestPipeQueue.enqueue(response), cq)

    case Event(result: Result, ManagerData(tl, dq, rpq, crawlerQueue)) if sender == crawler =>
      log.info(s"Storing result ${result.task.id} from Crawler to send at ResultPipeline later")
      stay using new ManagerData(tl, dq, rpq, crawlerQueue.enqueue(result))
  })

  when(ResultPipelineActive)(fromCrawler orElse fromResultPipeline orElse errorHandler orElse {
    case Event(PipelineStarted, ManagerData(taskList, downloaderQueue, _, _)) if sender == requestPipeline =>
      downloaderQueue.foreach(requestPipeline ! new ToLeft(_))

      if (taskList.isEmpty) {
        goto(Active)
      } else {
        val (task, remaining) = taskList.dequeue
        self ! new NextTask(task)
        goto(Active) using new ManagerData(remaining)
      }

    case Event(PipelineRestarting, _) if sender == resultPipeline =>
      goto(Created)

    case Event(Work(tasks), ManagerData(taskList, _, _, _)) =>
      log.info(s"Storing tasks $tasks from Master to send at RequestPipeline later")
      stay using new ManagerData(taskList.enqueue(collection.immutable.Seq(tasks: _*)))

    case Event(response: Response, ManagerData(tl, downloaderQueue, rpq, cq)) if sender == downloader =>
      log.info(s"Storing response ${response.task.id} from Downloader to send at RequestPipeline later")
      stay using new ManagerData(tl, downloaderQueue.enqueue(response), rpq, cq)
  })

  when(Active, stateTimeout = retryTimeout)(fromMaster orElse fromSelf orElse fromDownloader orElse
    fromRequestPipeline orElse fromResultPipeline orElse fromCrawler orElse errorHandler orElse {
    case Event(StateTimeout, ManagerData(Queue(), _, _, _)) =>
      log.debug(s"Received StateTimeout. Requesting work from Master")
      master ! new PullWork(batchSize)
      stay()

    case Event(PipelineRestarting, _) if sender == requestPipeline =>
      log.info("RequestPipeline is restarting")
      goto(ResultPipelineActive)

    case Event(PipelineRestarting, _) if sender == resultPipeline =>
      log.info("ResultPipeline is restarting")
      goto(RequestPipelineActive)
  })

  initialize()
}
