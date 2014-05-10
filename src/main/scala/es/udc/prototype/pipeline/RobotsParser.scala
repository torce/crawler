package es.udc.prototype.pipeline

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
            currentEntry.addRule(new Rule(value))
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

class Rule(_path: String) {
  def allowed(path: String): Boolean = {
    _path.isEmpty || !(_path == "*" || _path == path || (_path.endsWith("/") && path.startsWith(_path)))
  }
}