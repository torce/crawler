package es.udc.scrawl

import akka.actor._
import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.language.postfixOps
import com.typesafe.config.Config
import akka.contrib.pattern.ClusterSingletonManager
import es.udc.scrawl.util.SingletonProxy
import es.udc.scrawl.pipeline._
import es.udc.scrawl.pipeline.ToRight
import es.udc.scrawl.pipeline.ToLeft
import akka.routing.FromConfig

/**
 * User: david
 * Date: 12/02/14
 * Time: 23:18
 */
trait StartUp {
  this: Actor =>
  def initMaster(config: Config, listener: ActorRef): ActorRef = {
    val masterProps = Props(Class.forName(config.getString("scrawl.master.class")), config, listener)
    context.actorOf(ClusterSingletonManager.props(
      singletonProps = _ => masterProps,
      singletonName = "master",
      terminationMessage = PoisonPill,
      role = None),
      name = "master-manager")
    context.actorOf(Props(classOf[SingletonProxy], Seq(self.path.name, "master-manager", "master")), "master-proxy")
  }

  def initDownloader(config: Config): ActorRef = {
    context.actorOf(Props(Class.forName(config.getString("scrawl.downloader.class"))), "downloader")
  }

  def initCrawler(config: Config): ActorRef = {
    context.actorOf(Props(Class.forName(config.getString("scrawl.crawler.class")), config).withRouter(FromConfig()), "crawler")
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
                       downloaderQueue: Queue[TaskWrapper] = Queue(),
                       requestPipeQueue: Queue[Response] = Queue(),
                       crawlerQueue: Queue[TaskWrapper] = Queue())

class Manager(config: Config, listener: ActorRef) extends Actor
with ActorLogging
with StartUp
with FSM[ManagerState, ManagerData] {

  case class NextTask(task: Task)

  val batchSize = config.getInt("scrawl.manager.batch-size")
  val retryTimeout = config.getInt("scrawl.manager.retry-timeout").milliseconds

  //Child actor references
  val master = initMaster(config, listener)
  val downloader = initDownloader(config)
  val crawler = initCrawler(config)
  val requestPipeline = initRequestPipeline(config)
  val resultPipeline = initResultPipeline(config)

  override def preStart() {
    log.debug("Requesting work from Master")
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
      log.debug("Task list empty. Requesting more work from Master")
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
    case Event(response@(Response(_, _, _, _) | Error(_, _)), _) if sender == downloader =>
      log.debug(s"Forwarding ${response.getClass.getName} ${response.asInstanceOf[TaskWrapper].task.id} to RequestPipeline")
      requestPipeline ! new ToLeft(response)
      stay()
  }

  val fromRequestPipeline: StateFunction = {
    case Event(request: Request, _) if sender == requestPipeline =>
      log.debug(s"Forwarding request ${request.task.id} to Downloader")
      downloader ! request
      stay()

    case Event(response: Response, _) if sender == requestPipeline =>
      log.debug(s"Forwarding Response ${response.task.id} to RequestPipeline")
      resultPipeline ! new ToRight(response)
      stay()

    case Event(error: Error, _) if sender == requestPipeline =>
      log.debug(s"Forwarding Error ${error.task.id} to Master")
      master ! error
      stay()
  }

  val fromResultPipeline: StateFunction = {
    case Event(response: Response, _) if sender == resultPipeline =>
      log.debug(s"Forwarding response ${response.task.id} to Crawler")
      crawler ! response
      stay()

    case Event(result@(Result(_, _) | Error(_, _)), _) if sender == resultPipeline =>
      log.debug(s"Forwarding ${result.getClass.getName} ${result.asInstanceOf[TaskWrapper].task.id} to ResultPipeline")
      master ! result
      stay()
  }

  val fromCrawler: StateFunction = {
    case Event(result@(Result(_, _) | Error(_, _)), _) if sender == crawler =>
      log.debug(s"Forwarding ${result.getClass.getName} ${result.asInstanceOf[TaskWrapper].task.id} to ResultPipeline")
      resultPipeline ! new ToLeft(result)
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

    case Event(result@(Result(_, _) | Error(_, _)), ManagerData(tl, dq, rpq, crawlerQueue)) if sender == crawler =>
      log.debug(s"Storing result ${result.asInstanceOf[TaskWrapper].task.id} from Crawler to send at ResultPipeline later")
      stay using new ManagerData(tl, dq, rpq, crawlerQueue.enqueue(result.asInstanceOf[TaskWrapper]))

    case Event(response@(Response(_, _, _, _) | Error(_, _)), ManagerData(tl, downloaderQueue, rpq, cq)) if sender == downloader =>
      log.debug(s"Storing response ${response.asInstanceOf[TaskWrapper].task.id} from Downloader to send at RequestPipeline later")
      stay using new ManagerData(tl, downloaderQueue.enqueue(response.asInstanceOf[TaskWrapper]), rpq, cq)
  }

  when(RequestPipelineActive)(fromMaster orElse fromSelf orElse fromDownloader orElse {
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
      log.debug(s"Forwarding request ${request.task.id} to Downloader")
      downloader ! request
      stay()

    case Event(response: Response, ManagerData(tl, dq, requestPipeQueue, cq)) if sender == requestPipeline =>
      log.debug(s"Storing response ${response.task.id} from RequestPipeline to send at ResultPipeline later")
      stay using new ManagerData(tl, dq, requestPipeQueue.enqueue(response), cq)

    case Event(error: Error, _) if sender == requestPipeline =>
      log.debug(s"Forwarding error ${error.task.id} from RequestPipeline to master")
      master ! error
      stay()

    case Event(result@(Result(_, _) | Error(_, _)), ManagerData(tl, dq, rpq, crawlerQueue)) if sender == crawler =>
      log.debug(s"Storing result ${result.asInstanceOf[TaskWrapper].task.id} from Crawler to send at ResultPipeline later")
      stay using new ManagerData(tl, dq, rpq, crawlerQueue.enqueue(result.asInstanceOf[TaskWrapper]))
  })

  when(ResultPipelineActive)(fromCrawler orElse fromResultPipeline orElse {
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
      log.debug(s"Storing tasks $tasks from Master to send at RequestPipeline later")
      stay using new ManagerData(taskList.enqueue(collection.immutable.Seq(tasks: _*)))

    case Event(response@(Response(_, _, _, _) | Error(_, _)), ManagerData(tl, downloaderQueue, rpq, cq)) if sender == downloader =>
      log.debug(s"Storing response ${response.asInstanceOf[TaskWrapper].task.id} from Downloader to send at RequestPipeline later")
      stay using new ManagerData(tl, downloaderQueue.enqueue(response.asInstanceOf[TaskWrapper]), rpq, cq)
  })

  when(Active, stateTimeout = retryTimeout)(fromMaster orElse fromSelf orElse fromDownloader orElse
    fromRequestPipeline orElse fromResultPipeline orElse fromCrawler orElse {
    case Event(StateTimeout, ManagerData(Queue(), _, _, _)) =>
      log.debug(s"Received StateTimeout. Requesting work from Master")
      master ! new PullWork(batchSize)
      stay()

    case Event(PipelineRestarting, _) if sender == requestPipeline =>
      log.debug("RequestPipeline is restarting")
      goto(ResultPipelineActive)

    case Event(PipelineRestarting, _) if sender == resultPipeline =>
      log.debug("ResultPipeline is restarting")
      goto(RequestPipelineActive)
  })

  whenUnhandled {
    case Event(e, s) =>
      log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
      stay()
  }
  initialize()
}
