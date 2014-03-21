package es.udc.prototype.pipeline

import com.typesafe.config.Config

/**
 * User: david
 * Date: 21/03/14
 * Time: 14:57
 */
class EmptyStage(config: Config) extends Stage {
  def active = {
    case m: Any if sender == right => left ! m
    case m: Any if sender == left => right ! m
  }
}
