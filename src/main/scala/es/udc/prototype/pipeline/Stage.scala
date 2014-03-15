package es.udc.prototype.pipeline

import akka.actor.{ActorRef, Actor}

/**
 * User: david
 * Date: 15/03/14
 * Time: 13:13
 */

case class LeftRight(left: ActorRef, right: ActorRef)

case object Initialized

trait Stage extends Actor {
  var left: ActorRef = _
  var right: ActorRef = _

  def active: Actor.Receive

  def receive = {
    case LeftRight(l, r) =>
      left = l
      right = r
      sender ! Initialized
      context.become(active)
  }
}
