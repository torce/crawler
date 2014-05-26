package es.udc.scrawl.pipeline

import com.typesafe.config.Config

/**
 * User: david
 * Date: 21/03/14
 * Time: 14:57
 */
// $COVERAGE-OFF$
class EmptyStage(config: Config) extends Stage {
  def active = {
    case m: Any if sender == right => left ! m
    case m: Any if sender == left => right ! m
  }
}
// $COVERAGE-ON$
