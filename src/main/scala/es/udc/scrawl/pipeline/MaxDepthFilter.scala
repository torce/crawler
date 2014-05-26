package es.udc.scrawl.pipeline

import es.udc.scrawl.{Result, Response}
import com.typesafe.config.Config

/**
 * User: david
 * Date: 21/03/14
 * Time: 18:52
 */
class MaxDepthFilter(config: Config) extends ResultFilter {

  val maxDepth = config.getInt("scrawl.max-depth-filter.max-depth")

  override def handleResponse(r: Response) = Some(r)

  override def handleResult(r: Result) = {
    if (r.task.depth >= maxDepth)
      Some(new Result(r.task, Seq()))
    else
      Some(r)
  }
}
