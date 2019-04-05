package ai.beyond.compute.logging

import ai.beyond.compute.agents.ComputeAgent
import akka.actor.ActorLogging
//import akka.event.LoggingAdapter
//import org.slf4j.LoggerFactory

// Extend the ActorLogging Trait with ComputeAgentLogging trait
// to add additional loggers for Job Status and Job Results
// Restricted only to the ComputeAgent class
trait ComputeAgentLogging extends ActorLogging { this: ComputeAgent ⇒


  /*
    INFORMATION: You can add any custom log adapter here. This would be
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


}
