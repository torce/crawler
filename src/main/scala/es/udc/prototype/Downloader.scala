package es.udc.prototype

import akka.actor.Actor

/**
 * User: david
 * Date: 13/02/14
 * Time: 19:47
 */
class Downloader extends Actor {
  def receive = {
    case Request(url, id) =>
      //TODO
      sender ! new Response(url, id, Map(), "")
  }
}
