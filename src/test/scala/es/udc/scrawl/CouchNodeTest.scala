package es.udc.scrawl

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, Matchers, WordSpecLike}
import es.udc.scrawl.test.util.SpyLinkExtractor
import sprouch.{Couch, Views, NewDocument, MapReduce}
import scala.concurrent.Await
import scala.concurrent.duration._
import sprouch.dsl._
import akka.io.IO
import spray.can.Http
import akka.io.Tcp.Bound

class CouchNodeTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("system.couch.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll
with BeforeAndAfterEach {

  val config = ConfigFactory.load("application.test.conf")
  val sprouchConfig = sprouch.Config(system)
  val couch = Couch(sprouchConfig)

  val msgTimeout = 5.seconds

  //Init the test server
  val server = system.actorOf(Props[NodeTestServer])
  IO(Http) ! Http.Bind(server, NodeTestServer.host, NodeTestServer.port)
  expectMsgPF() {
    case Bound(_) => true
  }

  //Ignore the Bound message

  override def afterAll() {
    system.shutdown()
  }

  override def beforeEach() {
    import system.dispatcher
    import sprouch.JsonProtocol._

    val dbName = config.getString("scrawl.master.couch.db-name")
    implicit val db = couch.createDb(dbName) recoverWith {
      case _ => for {
        _ <- couch.deleteDb(dbName)
        database <- couch.createDb(dbName)
      } yield database
    }

    val newTasks = MapReduce(map =
      """function(doc) {
           if(doc.status.status && doc.status.status === 'New') {
             emit(doc._id);
           }
         }
      """.stripMargin)

    val started = MapReduce(map =
      """function(doc) {
           if(doc.status.status && doc.status.status === 'InProgress') {
             emit(doc.status.started)
           }
         }""".stripMargin)

    val remaining = MapReduce(map =
      """function(doc) {
         if(doc.status.status && ((doc.status.status === 'New') || (doc.status.status === 'InProgress'))) {
           emit(null, 1);
         } else {
           emit(null, 0);
         }
       }""",
      reduce =
        """function(keys, values, rereduce) {
         return sum(values);
       }""".stripMargin)

    Await.result(NewDocument("crawler",
      Views(Map("new-tasks" -> newTasks,
        "started" -> started,
        "remaining" -> remaining))).createViews, 10.seconds)
  }

  "A Node" should {
    "retrieve task from initial URL and crawl it" in {
      import NodeTestServer.makeUrl
      val expected = Set(makeUrl("/"), makeUrl("/resource"), makeUrl("/stuff"))

      //Loaded also in the constructor, sbt does not execute test if there are constructor arguments
      val config = ConfigFactory.load("system.test.conf").withFallback(ConfigFactory.load())

      system.actorOf(Props(classOf[Manager], config, self), "manager")

      expectMsg(msgTimeout, Started)
      system.actorSelection("/user/manager/master-proxy") ! new NewTasks(Seq(makeUrl("/")))


      expectMsgPF(msgTimeout) {
        case Finished => SpyLinkExtractor.visitedPaths should be(expected)
      }
    }
  }
}
