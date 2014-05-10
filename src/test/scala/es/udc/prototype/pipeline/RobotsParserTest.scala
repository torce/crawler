package es.udc.prototype.pipeline

import org.scalatest.{Matchers, WordSpecLike}
import spray.http.Uri

class RobotsParserTest extends WordSpecLike with Matchers {
  "A RobotsParser" should {
    "disallow paths by user agent" in {
      val robotsFile =
        """User-Agent: Mozilla
          |Disallow: /path
        """.stripMargin
      val robots = RobotsParser(robotsFile)

      robots.allowed("Mozilla", Uri("/path")) shouldBe false
      robots.allowed("Opera", Uri("/path")) shouldBe true
    }
    "disallow directories an their contents by user agent" in {
      val robotsFile =
        """User-Agent: Mozilla
          |Disallow: /dir/
        """.stripMargin
      val robots = RobotsParser(robotsFile)

      robots.allowed("Mozilla", Uri("/dir/")) shouldBe false
      robots.allowed("Mozilla", Uri("/dir/path")) shouldBe false
      robots.allowed("Opera", Uri("/dir/")) shouldBe true
      robots.allowed("Opera", Uri("/dir/path")) shouldBe true
    }
    "ignore lines with comments" in {
      val robotsFile =
        """User-Agent: Mozilla #Comment
          |#Whole line comment
          |  #Spaces before comment
          |Disallow: /path #Comment
        """.stripMargin
      val robots = RobotsParser(robotsFile)

      robots.allowed("Mozilla", Uri("/path")) shouldBe false
      robots.allowed("Opera", Uri("/path")) shouldBe true
    }
    "apply the * rules for all user agents" in {
      val robotsFile =
        """User-Agent: *
          |Disallow: /path
        """.stripMargin
      val robots = RobotsParser(robotsFile)

      robots.allowed("Mozilla", Uri("/path")) shouldBe false
      robots.allowed("Opera", Uri("/path")) shouldBe false
    }
    "allow multiple user agent rules" in {
      val robotsFile =
        """User-Agent: Mozilla
          |User-Agent: Opera
          |Disallow: /path
        """.stripMargin
      val robots = RobotsParser(robotsFile)

      robots.allowed("Mozilla", Uri("/path")) shouldBe false
      robots.allowed("Opera", Uri("/path")) shouldBe false
      robots.allowed("Chrome", Uri("/path")) shouldBe true
    }
    "allow multiple disallow rules" in {
      val robotsFile =
        """User-Agent: Mozilla
          |Disallow: /ping
          |Disallow: /pong
        """.stripMargin
      val robots = RobotsParser(robotsFile)

      robots.allowed("Mozilla", Uri("/ping")) shouldBe false
      robots.allowed("Mozilla", Uri("/pong")) shouldBe false
      robots.allowed("Opera", Uri("/ping")) shouldBe true
      robots.allowed("Opera", Uri("/pong")) shouldBe true
    }
    "use specific user agent rules instead of the default rule *, if possible" in {
      val robotsFile =
        """User-Agent: *
          |Disallow: /path
          |User-Agent: Mozilla
          |Disallow:
        """.stripMargin
      val robots = RobotsParser(robotsFile)
      robots.allowed("Mozilla", Uri("/path")) shouldBe true
      robots.allowed("Opera", Uri("/path")) shouldBe false
    }
  }
}
