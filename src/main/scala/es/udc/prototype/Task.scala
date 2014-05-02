package es.udc.prototype

import spray.http.Uri

object Task {
  def unapply(task: Task): Option[(String, Uri, Int)] = Some((task.id, task.url, task.depth))
}

trait Task {
  def id: String

  def depth: Int

  def url: Uri
}