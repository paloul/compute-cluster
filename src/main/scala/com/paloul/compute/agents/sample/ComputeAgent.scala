package com.paloul.compute.agents.sample

import java.util.concurrent.Executors
import akka.actor.Actor
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import com.paloul.compute.Settings
import com.paloul.compute.agents.util.{PerformanceMetrics, RequestAgentTimeout}
import com.paloul.compute.logging.sample.ComputeAgentLogging
import com.paloul.compute.agents.sample.computeagentprotocol._

object ComputeAgent {
  // Execution Pool/Context for Processing Data, these allow agents to perform
  // long-running tasks on a different thread pool separate from main message handler
  private val procExecutionContext = ExecutionContext.fromExecutorService(
    // Creates a work-stealing thread pool using all available
    // processors as its target parallelism level
    Executors.newWorkStealingPool()
  )
}

class ComputeAgent(settings: Settings) extends Actor with
        ComputeAgentLogging with RequestAgentTimeout with PerformanceMetrics {


  // Import all available functions under the context handle, i.e. become, actorSelection, system
  //import context._

  // Import the Execution Context for background tasks
  import ComputeAgent.procExecutionContext


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

    case DoWork() =>
      log.info("I [{}] am starting my work....", agentId)
      doMyWork()

  }
  //------------------------------------------------------------------------//
  // End Actor Receive Behavior
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Begin Compute Functions
  //------------------------------------------------------------------------//
  def doMyWork(): Future[Unit] = Future {

    log.info("I [{}] started my work!", agentId)

    Thread.sleep(5000)

    log.info("I [{}] finished my work!", agentId)

  }(procExecutionContext)
  //------------------------------------------------------------------------//
  // End Compute Functions
  //------------------------------------------------------------------------//

}
