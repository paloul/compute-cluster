package com.paloul.compute.agents.sample

import akka.actor.Actor
//import akka.pattern.ask
//import akka.util.Timeout
import com.paloul.compute.Settings
import com.paloul.compute.agents.util.{PerformanceMetrics, RequestAgentTimeout}
import com.paloul.compute.logging.sample.ComputeAgentLogging
import com.paloul.compute.agents.sample.computeagentprotocol._

class ComputeAgent(settings: Settings) extends Actor with
        ComputeAgentLogging with RequestAgentTimeout with PerformanceMetrics {


  // Import all available functions under the context handle, i.e. become, actorSelection, system
  //import context._


  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def agentId: String = self.path.name
  def agentPath: String = self.path.toStringWithoutAddress


  ///////////////////////
  // Private Read-Only Parameters
  // Default timeout for Ask patterns to other agents (even self)
  // Implicit so it can just be used where necessary
  //private implicit val TIMEOUT: Timeout = requestAgentTimeout(settings)
  ///////////////////////


  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("Compute Agent - {} - starting", agentPath)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "Compute Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("Compute Agent - {} - stopped", agentPath)
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Begin Actor Receive Behavior
  //------------------------------------------------------------------------//
  override def receive: Receive = idle // Set the initial behavior to idle

  // Idle behavior state
  def idle: Receive = {

    // Commands //
    case PrintPath() =>
      log.info("My, [{}], path is {}", agentId, agentPath)

    case RepeatMe(saying) =>
      log.info("Hello there, I am [{}], and you said, '{}'", agentId, saying)

  }
  //------------------------------------------------------------------------//
  // End Actor Receive Behavior
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Begin Compute Functions
  //------------------------------------------------------------------------//

  //------------------------------------------------------------------------//
  // End Compute Functions
  //------------------------------------------------------------------------//

}
