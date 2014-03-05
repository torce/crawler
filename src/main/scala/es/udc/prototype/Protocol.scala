package es.udc.prototype

/**
 * User: david
 * Date: 12/02/14
 * Time: 21:09
 */
//Not a message
case class Task(id: String, url: String)

//? -> Master
case class NewTasks(links: Seq[String])

//Master -> ?
case object Started

case object Finished

//BaseCrawler -> Manager -> Master
case class Result(task: Task, links: Seq[String])

//Manager -> Master
case class PullWork(size: Int)

//Master -> Manager
case class Work(tasks: Seq[Task])

//Manager -> Downloader
case class Request(task: Task, headers: Map[String, String])

//Downloader -> Manager -> BaseCrawler
case class Response(task: Task, headers: Map[String, String], body: String)
