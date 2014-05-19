package es.udc.prototype.pipeline

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import es.udc.prototype.master.DefaultTask
import es.udc.prototype.{Error, Response, Task, Request}
import spray.http.{StatusCodes, Uri}
import scala.concurrent.duration._
import spray.http.Uri.Empty

class RobotsFilterTest extends TestKit(ActorSystem("test-system", ConfigFactory.load("application.test.conf")))
with ImplicitSender
with WordSpecLike
with Matchers
with BeforeAndAfterAll {

  override def afterAll() {
    system.shutdown()
  }

  val config = ConfigFactory.load("application.test.conf")

  val msgDelay = 100.milliseconds
  val retryTimeout = config.getInt("prototype.robots-filter.retry-timeout").milliseconds

  def initRobots() = {
    val retry = system.actorOf(Props(classOf[RobotsFilter], config))
    val listener = TestProbe()
    val left = TestProbe()
    val right = TestProbe()
    listener.send(retry, new LeftRight(left.ref, right.ref))
    listener.expectMsg(Initialized)
    (retry, left, right)
  }

  "A RobotsFilter stage" should {
    "send a Request for robots.txt when is not in cache using the same headers" in {
      val (robots, left, right) = initRobots()
      val request = new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map("User-Agent" -> "Mozilla"))
      left.send(robots, request)
      right.expectMsgPF() {
        case Request(Task(_, url, _), request.headers) if url == Uri("http://test.com/robots.txt") => Unit
      }
    }
    "store robots.txt files for all the authorities" in {
      val (robots, left, right) = initRobots()
      val robotsFile =
        """User-Agent: *
          |Disallow: /path
        """.stripMargin
      right.send(robots, new Response(new DefaultTask("id", Uri("http://test.com/robots.txt"), 0), StatusCodes.OK, Map(), robotsFile))

      //Drop this
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map("User-Agent" -> "Mozilla")))
      //Allow this
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/test"), 0), Map("User-Agent" -> "Mozilla")))
      //Check
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/test")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
    }
    "resend the request cached while waiting for robots.txt, sending an error for each request denied" in {
      val (robots, left, right) = initRobots()
      val robotsFile =
        """User-Agent: *
          |Disallow: /path
        """.stripMargin

      //Cache all the requests to //test.com
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map("User-Agent" -> "Mozilla")))
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/test"), 0), Map("User-Agent" -> "Mozilla")))

      //The stage request the robots.txt file
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.send(robots, new Response(new DefaultTask("id", Uri("http://test.com/robots.txt"), 0), StatusCodes.OK, Map(), robotsFile))

      //After sending the robots.txt file, the cached responses are sent to the right
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/test")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      //The paths denied are sent to the left as an error
      left.expectMsgPF() {
        case Error(DefaultTask("id", uri, 0), RobotsPathFiltered("Mozilla"))
          if uri == Uri("http://test.com/robots.txt") => Unit
      }
    }

    "allow all the requests if the error code is not 200 OK requesting robots.txt" in {
      val (robots, left, right) = initRobots()

      //Cache all the requests to //test.com
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map("User-Agent" -> "Mozilla")))
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/test"), 0), Map("User-Agent" -> "Mozilla")))

      //The stage request the robots.txt file
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.send(robots, new Response(new DefaultTask("id", Uri("http://test.com/robots.txt"), 0), StatusCodes.NotFound, Map(), ""))

      //After sending an error in robots.txt file, all the cached responses are sent to the right in any order
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/path") || url == Uri("http://test.com/test")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/path") || url == Uri("http://test.com/test")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
    }
    "allow all the requests if there are any errors requesting robots.txt" in {
      val (robots, left, right) = initRobots()

      //Cache all the requests to //test.com
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map("User-Agent" -> "Mozilla")))
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/test"), 0), Map("User-Agent" -> "Mozilla")))

      //The stage request the robots.txt file
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.send(robots, new Error(new DefaultTask("id", Uri("http://test.com/robots.txt"), 0), new Exception))

      //After sending an error in robots.txt file, all the cached responses are sent to the right in any order
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/path") || url == Uri("http://test.com/test")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/path") || url == Uri("http://test.com/test")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
    }

    "allow all the requests if there are any errors parsing robots.txt" in {
      val (robots, left, right) = initRobots()

      //Cache all the requests to //test.com
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map("User-Agent" -> "Mozilla")))
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/test"), 0), Map("User-Agent" -> "Mozilla")))

      //The stage request the robots.txt file
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.send(robots, new Response(new DefaultTask("id", Uri("http://test.com/robots.txt"), 0), StatusCodes.OK, Map(), "Lorem ipsum"))

      //After parsing a wrong robots.txt file, all the cached responses are sent to the right in any order
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/path") || url == Uri("http://test.com/test")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/path") || url == Uri("http://test.com/test")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
    }

    "drop the requests not allowed by robots.txt, sending an error to the left" in {
      val (robots, left, right) = initRobots()
      val robotsFile =
        """User-Agent: *
          |Disallow: /path
        """.stripMargin

      //Cache all the requests to //test.com
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map("User-Agent" -> "Mozilla")))

      //The stage request the robots.txt file
      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.send(robots, new Response(new DefaultTask("id", Uri("http://test.com/robots.txt"), 0), StatusCodes.OK, Map(), robotsFile))
      left.expectMsgPF() {
        case Error(DefaultTask("id", uri, 0), RobotsPathFiltered("Mozilla"))
          if uri == Uri("http://test.com/robots.txt") => Unit
      }
      right.expectNoMsg()
    }

    "do not modify the responses, even with forbidden paths" in {
      val (robots, left, right) = initRobots()
      val robotsFile =
        """User-Agent: *
          |Disallow: /path
        """.stripMargin
      val robotsResponse = new Response(new DefaultTask("id", Uri("http://test.com/robots.txt"), 0), StatusCodes.OK, Map(), robotsFile)
      right.send(robots, robotsResponse)
      left.expectMsg(robotsResponse)
      val response = new Response(new DefaultTask("id", Uri("http://test.com/path"), 0), StatusCodes.OK, Map(), "body")
      right.send(robots, response)
      left.expectMsg(response)
    }

    "do not modify the robot.txt responses from another stages" in {
      val (robots, left, right) = initRobots()
      val robotsFile =
        """User-Agent: *
          |Disallow: /path
        """.stripMargin
      val response = new Response(new DefaultTask("id", Uri("http://test.com/path"), 0), StatusCodes.OK, Map(), robotsFile)
      right.send(robots, response)
      left.expectMsg(response)
    }

    "use the * wildcard if no user agent defined" in {
      val (robots, left, right) = initRobots()
      val robotsFile =
        """User-Agent: *
          |Disallow: /path
        """.stripMargin
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map()))

      //The stage request the robots.txt file
      right.expectMsgPF() {
        case Request(Task(_, url, _), _)
          if url == Uri("http://test.com/robots.txt") => Unit
      }
      right.send(robots, new Response(new DefaultTask("id", Uri("http://test.com/robots.txt"), 0), StatusCodes.OK, Map(), robotsFile))

      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map()))
      right.expectNoMsg()
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/test"), 0), Map()))
      right.expectMsgPF() {
        case Request(Task(_, url, _), _)
          if url == Uri("http://test.com/test") => Unit
      }
    }

    "send all the error messages to the left" in {
      val (filter, left, _) = initRobots()
      val error = new Error(new DefaultTask("unhandled", Empty, 0), new Exception)

      filter ! error
      left.expectMsg(error)
    }

    "restart robots.txt request after a timeout" in {
      val (robots, left, right) = initRobots()
      //Cache the request to //test.com
      left.send(robots, new Request(new DefaultTask("id", Uri("http://test.com/path"), 0), Map("User-Agent" -> "Mozilla")))

      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }

      //Cache the request to //ping.com
      left.send(robots, new Request(new DefaultTask("id", Uri("http://ping.com/test"), 0), Map("User-Agent" -> "Mozilla")))

      right.expectMsgPF() {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://ping.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }

      //Retry the robots.txt request after a timeout, wait for the second tick plus a delay
      right.expectMsgPF(2 * retryTimeout + msgDelay) {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://test.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
      right.expectMsgPF(2 * retryTimeout + msgDelay) {
        case Request(Task(_, url, _), headers)
          if url == Uri("http://ping.com/robots.txt")
            && headers == Map("User-Agent" -> "Mozilla") => Unit
      }
    }
  }
}
