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
  private var _left: ActorRef = _
  private var _right: ActorRef = _

  def left = _left

  def right = _right

  def active: Actor.Receive

  final def receive = {
    case LeftRight(l, r) =>
      _left = l
      _right = r
      sender ! Initialized
      context.become(active)
  }
}
