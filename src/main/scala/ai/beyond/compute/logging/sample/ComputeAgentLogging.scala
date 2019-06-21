package ai.beyond.compute.logging.sample

import ai.beyond.compute.agents.sample.ComputeAgent
import akka.actor.ActorLogging
import akka.event.LoggingAdapter

// Extend the ActorLogging Trait with ComputeAgentLogging trait
// to add additional loggers for Job Status and Job Results
// Restricted only to the ComputeAgent class
trait ComputeAgentLogging extends ActorLogging { this: ComputeAgent ⇒

  /*
    NOTE: You can add any custom log adapter here. This would be
      specific for ComputeAgents since if you can only add this trait to
      ComputeAgents. This will allow you to create multiple log adapters
      that utilize different logback.xml loggers/appenders.
      Look below to the commented out code. You can also have access
      to the underlying default 'log' variable created by the base
      ActorLogging trait that this one extends.
   */
  /*private var _logJobStatus: LoggingAdapter = _

  def logJobStatus: LoggingAdapter = {
    // only used in Actor, i.e. thread safe
    if (_logJobStatus eq null) {
      _logJobStatus = akka.event.Logging(context.system, this)
    }

    _logJobStatus
  }*/

  /**
    * Implicit logging adapter to make it easy to pass Akka logging
    * mechanism down to supporting classes outside of actors
    * @return Default Logging Adapter available from ActorLogging
    */
  implicit def logJobStatus: LoggingAdapter = {
    log
  }

  /**
    * Will measure time elapsed of the func block passed.
    * Use it like this var list = time {List.range(1,1000, 1)} where
    * you can replace the execution block inside the curly braces with
    * anything you want to measure. The response of the execution block
    * will be returned back. Think of this as a wrapper
    * @param name Name of func block for log output
    * @param block Execution block
    * @tparam R Return from execution block
    * @return
    */
  implicit def time[R](name:String, block: => R): R = {
    val t0 = System.nanoTime()
    val result = block // call-by-name, will execute func block
    val t1 = System.nanoTime()

    log.info("[{}] Elapsed time: {} (s)", name, (t1 - t0) / 1E9) // Print out in seconds (nano / 1E9)

    result // return the result from func block
  }

}
