package es.udc.prototype

import spray.http.Uri

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:09
 */
//Not a message
case class Task(id: String, url: Uri)

//? -> Master
case class NewTasks(links: Seq[Uri])

//Master -> ?
case object Started

case object Finished

//BaseCrawler -> Manager -> Master
case class Result(task: Task, links: Seq[Uri])

//Manager -> Master
case class PullWork(size: Int)

//Master -> Manager
case class Work(tasks: Seq[Task])

//Manager -> Downloader
case class Request(task: Task, headers: Map[String, String])

//Downloader -> Manager -> BaseCrawler
case class Response(task: Task, headers: Map[String, String], body: String)
