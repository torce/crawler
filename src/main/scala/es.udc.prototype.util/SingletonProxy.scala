package es.udc.prototype.util

import akka.actor.{RootActorPath, ActorSelection, ActorLogging, Actor}
import akka.cluster.ClusterEvent.{MemberRemoved, MemberUp, CurrentClusterState, MemberEvent}
import akka.cluster.{Cluster, MemberStatus, Member}
import scala.collection.immutable

/**
 * User: david
 * Date: 12/02/14
 * Time: 20:37
 */
class SingletonProxy(singletonPathSeq: Seq[String]) extends Actor with ActorLogging {
  override def preStart() : Unit =
    Cluster(context.system).subscribe(self, classOf[MemberEvent])
  override def postStop() : Unit =
    Cluster(context.system).unsubscribe(self)

  val ageOrdering = Ordering.fromLessThan[Member] { (a,b) => a.isOlderThan(b) }
  var membersByAge: immutable.SortedSet[Member] =
    immutable.SortedSet.empty(ageOrdering)

  def receive = {
    case state: CurrentClusterState =>
      membersByAge = immutable.SortedSet.empty(ageOrdering) ++ state.members.filter(m =>
        m.status == MemberStatus.Up)
    case MemberUp(m) => membersByAge += m
    case MemberRemoved(m, _) => membersByAge -= m
    case other =>
      val currentSender = sender
      val c = consumer
      c foreach {
        _.tell(other, currentSender)
      }
  }

  def consumer: Option[ActorSelection] =
    membersByAge.headOption map (m => context.actorSelection(
      RootActorPath(m.address) / "user" / singletonPathSeq))
}
