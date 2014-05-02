package es.udc.prototype.master

import spray.json._
import sprouch.RevedDocument
import spray.http.Uri
import es.udc.prototype.master.Master._
import es.udc.prototype.master.Master.InProgress
import es.udc.prototype.master.MasterCouch.{NewCouchTask, RevedCouchTask}

object CouchTaskJsonProtocol extends DefaultJsonProtocol {

  implicit object CouchTaskFormat extends RootJsonFormat[RevedCouchTask] {
    def write(t: RevedCouchTask) = {
      JsObject("_id" -> JsString(t.id),
        "_rev" -> JsString(t.rev),
        "url" -> t.url.toJson,
        "depth" -> JsNumber(t.depth),
        "status" -> t.status.toJson)
    }

    def read(value: JsValue) = {
      value.asJsObject.getFields("_id", "_rev", "url", "depth", "status") match {
        case Seq(id: JsString, rev: JsString, url: JsString, depth: JsNumber, status: JsObject) =>
          new RevedCouchTask(new RevedDocument[NewCouchTask](id.value, rev.value,
            new NewCouchTask(url.convertTo[Uri], depth.value.toInt, status.convertTo[TaskStatus]), Map()))
      }
    }
  }

  implicit object UriJsonFormat extends JsonFormat[Uri] {
    def write(u: Uri) = JsString(u.toString())

    def read(value: JsValue) = Uri(value.asInstanceOf[JsString].value)
  }

  implicit object NullJsonFormat extends JsonFormat[Null] {
    def write(n: Null) = JsNull

    def read(value: JsValue) = {
      if (value == JsNull) {
        null
      } else {
        deserializationError("Null expected")
      }
    }
  }

  implicit object TaskStatusJsonFormat extends RootJsonFormat[TaskStatus] {
    def write(t: TaskStatus) = t match {
      case New => JsObject("status" -> JsString("New"))
      case Completed => JsObject("status" -> JsString("Completed"))
      case InProgress(started) => JsObject("status" -> JsString("InProgress"),
        "started" -> JsNumber(started))
      case WithError(e) => JsObject("status" -> JsString("WithError"),
        "reason" -> JsString(e))
    }

    def read(value: JsValue) = {
      val objectValue = value.asJsObject
      objectValue.getFields("status") match {
        case Seq(JsString("New")) => New
        case Seq(JsString("Completed")) => Completed
        case Seq(JsString("InProgress")) => new InProgress(objectValue.fields("started").asInstanceOf[JsNumber].value.toLong)
        case Seq(JsString("WithError")) => new WithError(objectValue.fields("reason").asInstanceOf[JsString].value)
      }
    }
  }

  implicit val taskWithStatusFormat = jsonFormat3(NewCouchTask)
}