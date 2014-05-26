package es.udc.scrawl.pipeline

import java.net.URLDecoder
import spray.http.Uri

object RobotsParser {

  object ParserStatus extends Enumeration {
    type ParserStatus = Value
    val Initial, UserAgentSaw, RuleSaw = Value
  }

  def apply(file: String): RobotsParser = {
    import ParserStatus._

    val lines = file.split('\n')
    var entries = Seq[Entry]()
    var defaultEntry: Option[Entry] = None
    var currentEntry = new Entry()
    var status = Initial

    def addEntry(entry: Entry) {
      if (entry.userAgents.contains("*")) {
        if (defaultEntry == None) {
          defaultEntry = Some(entry)
        }
      } else {
        entries = entry +: entries
      }
    }

    for (line <- lines) {
      if (line.isEmpty)
        if (status == UserAgentSaw) {
          currentEntry = new Entry()
          status = Initial
        } else if (status == RuleSaw) {
          addEntry(currentEntry)
          currentEntry = new Entry()
          status = Initial
        }

      //Remove comment
      val commentIndex = line.indexOf('#')
      val cleanLine = if (commentIndex == -1) {
        line
      } else if (commentIndex > 0) {
        line.substring(0, commentIndex)
      } else {
        "" //The whole line is a comment
      }

      if (!cleanLine.isEmpty) {
        val pair = cleanLine.split(":", 2)
        val (key, value) = pair.size match {
          case 2 => pair(0).toLowerCase -> URLDecoder.decode(pair(1).trim(), "UTF-8")
          case 1 => pair(0).toLowerCase -> ""
        }
        if (key == "user-agent") {
          if (status == RuleSaw) {
            addEntry(currentEntry)
            currentEntry = new Entry()
          }
          currentEntry.addUserAgent(value.toLowerCase)
          status = UserAgentSaw
        } else if (key == "disallow") {
          if (status != Initial) {
            val rulePair = value.split("\\*", 2).toSeq
            if (rulePair.size == 1) {
              currentEntry.addRule(new BasicRule(value))
            } else if (rulePair(0).isEmpty && rulePair(1).isEmpty) {
              currentEntry.addRule(new WildcardAllRule)
            } else if (rulePair(0).isEmpty) {
              currentEntry.addRule(new WildcardEndRule(value.substring(1, value.length)))
            } else if (rulePair(1).isEmpty) {
              currentEntry.addRule(new WildcardStartRule(value.substring(0, value.length - 1)))
            } else {
              currentEntry.addRule(new WilcardStartEndRule(rulePair(0), rulePair(1)))
            }
            status = RuleSaw
          }
        }
      }
    }
    if (status == RuleSaw) {
      addEntry(currentEntry)
    }

    new RobotsParser(entries, defaultEntry)
  }
}

class RobotsParser(entries: Seq[Entry], defaultEntry: Option[Entry]) {
  def allowed(userAgent: String, url: Uri): Boolean = {
    val parsedUrl = url.toRelative.path.toString()
    val parsedUserAgent = userAgent.split('/')(0).toLowerCase
    for (entry <- entries) {
      if (entry.userAgents.contains(parsedUserAgent)) {
        return entry.allowed(parsedUrl)
      }
    }
    if (defaultEntry != None) {
      return defaultEntry.get.allowed(parsedUrl)
    }
    true
  }
}

class Entry {
  var _userAgents = Set[String]()
  var _rules = Seq[Rule]()

  def userAgents = _userAgents

  def rules = _rules

  def addUserAgent(userAgent: String) {
    _userAgents = _userAgents + userAgent
  }

  def addRule(rule: Rule) {
    _rules = rule +: _rules
  }

  def allowed(path: String): Boolean = {
    for (rule <- _rules) {
      if (!rule.allowed(path))
        return false
    }
    true
  }
}

trait Rule {
  def allowed(path: String): Boolean
}

class BasicRule(_path: String) extends Rule {
  def allowed(path: String): Boolean = {
    _path.isEmpty || !(_path == path || (_path.endsWith("/") && path.startsWith(_path)))
  }
}

class WildcardAllRule extends Rule {
  def allowed(path: String): Boolean = false
}

class WildcardStartRule(_path: String) extends Rule {
  def allowed(path: String): Boolean = !path.startsWith(_path)
}

class WildcardEndRule(_path: String) extends Rule {
  def allowed(path: String): Boolean = !path.endsWith(_path)
}

class WilcardStartEndRule(_start: String, _end: String) extends Rule {
  def allowed(path: String): Boolean = !path.startsWith(_start) && !path.endsWith(_end)
}