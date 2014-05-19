package es.udc.prototype

import spray.http.{StatusCode, Uri}

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:09
 */

//? -> Master
case class NewTasks(links: Seq[Uri])

//Master -> ?
case object Started

case object Finished

sealed trait TaskWrapper {
  def task: Task
}

//BaseCrawler -> Manager -> Master
case class Result(task: Task, links: Seq[Uri]) extends TaskWrapper

//Manager -> Master
case class PullWork(size: Int)

//Master -> Manager
case class Work(tasks: Seq[Task])

//Manager -> Downloader
case class Request(task: Task, headers: Map[String, String]) extends TaskWrapper

//Downloader -> Manager -> BaseCrawler
case class Response(task: Task, status: StatusCode, headers: Map[String, String], body: String) extends TaskWrapper

//? -> Master
case class Error(task: Task, reason: Throwable) extends TaskWrapper