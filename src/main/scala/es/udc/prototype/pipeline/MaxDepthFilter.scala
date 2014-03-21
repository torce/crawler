package es.udc.prototype.pipeline

import es.udc.prototype.{Result, Response}
import com.typesafe.config.Config

/**
 * User: david
 * Date: 21/03/14
 * Time: 18:52
 */
class MaxDepthFilter(config: Config) extends ResultFilter {

  val maxDepth = config.getInt("prototype.max-depth-filter.max-depth")

  override def handleResponse(r: Response): Response = r

  override def handleResult(r: Result): Result = {
    if (r.task.depth >= maxDepth)
      new Result(r.task, Seq())
    else
      r
  }
}
