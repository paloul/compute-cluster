package com.paloul.compute.agents.util

import akka.event.LoggingAdapter

trait PerformanceMetrics {

  /**
    * Will measure time elapsed of the func block passed.
    * Use it like this var list = time {List.range(1,1000, 1)} where
    * you can replace the execution block inside the curly braces with
    * anything you want to measure. The response of the execution block
    * will be returned back. Think of this as a wrapper
    * @param name Name of func block for log output
    * @param block Execution block
    * @tparam R Return from execution block
    * @param log Implicitly passed in akka.event.LoggingAdapter
    * @return R The Return from execution block
    */
  def time[R](name:String, block: => R)(implicit log: LoggingAdapter): R = {
    val t0 = System.nanoTime()
    val result = block // call-by-name, will execute func block
    val t1 = System.nanoTime()

    log.info("[{}] Elapsed time: {} (s)", name, (t1 - t0) / 1E9) // Print out in seconds (nano / 1E9)

    result // return the result from func block
  }

}
