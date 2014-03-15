package es.udc.prototype.pipeline

import akka.actor._
import com.typesafe.config.Config
import scala.collection.mutable.{Map => MMap}
import scala.concurrent.duration._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy.{Restart, Escalate}

/**
 * User: david
 * Date: 15/03/14
 * Time: 15:24
 */

/**
 * When the pipeline is ready to receive messages, this message is sended.
 */
case object PipelineStarted

/**
 * When a stage is restarting, this message is sended to the parent actor.
 * Until the PipelineStarted message is sended, all received messages are dropped.
 */
case object PipelineRestarting

trait StartStages {
  this: Actor =>
  def initStages(config: Config): Seq[ActorRef] = {
    import collection.JavaConversions._
    //TODO Find a better solution. For now, use the actor name as prefix to read config
    config.getStringList(self.path.name + ".stages").toIndexedSeq.map {
      s =>
        context.actorOf(Props(Class.forName(s), config))
    }
  }
}

object Pipeline {

  object StageStatus extends Enumeration {
    type StageStatus = Value
    val Created, Active = Value
  }

}

class Pipeline(config: Config) extends Actor with StartStages {

  import Pipeline.StageStatus._

  var stages: MMap[ActorRef, (StageStatus, ActorRef, ActorRef)] = MMap()
  var firstStage: ActorRef = _
  var lastStage: ActorRef = _

  var activeStages: Int = 0

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute) {
      case _: ActorInitializationException => Escalate
      case _: ActorKilledException => Escalate
      case _: Exception =>
        val oldStatus = stages(sender)
        if (oldStatus._1 == Active)
          activeStages -= 1
        stages.put(sender, (Created, oldStatus._2, oldStatus._3))
        context.become(receive)
        context.parent ! PipelineRestarting
        sender ! LeftRight(oldStatus._2, oldStatus._3)
        Restart
    }

  override def preStart() {
    val seqStages = initStages(config)
    seqStages.view.zipWithIndex.foreach {
      // First stage
      case (s, 0) =>
        firstStage = s
        stages.put(s, (Created, self, seqStages(1)))
        s ! new LeftRight(self, seqStages(1))
      // Last stage
      case (s, i) if i == seqStages.size - 1 =>
        lastStage = s
        stages.put(s, (Created, seqStages(i - 1), self))
        s ! new LeftRight(seqStages(i - 1), self)
      // Middle stages
      case (s, i) =>
        stages.put(s, (Created, seqStages(i - 1), seqStages(i + 1)))
        s ! new LeftRight(seqStages(i - 1), seqStages(i + 1))
    }
  }

  def active: Actor.Receive = {
    case m if sender == context.parent =>
      firstStage ! m
    case m if sender == lastStage =>
      context.parent ! m
  }

  def receive = {
    case Initialized =>
      val oldStatus = stages(sender)
      stages.put(sender, (Active, oldStatus._2, oldStatus._3))
      activeStages += 1
      if (activeStages == stages.size) {
        context.parent ! PipelineStarted
        context.become(active)
      }
  }
}
