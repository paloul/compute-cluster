package ai.beyond.compute.agents.aira

import ai.beyond.compute.agents.util.db.MongoMasterAgent
import ai.beyond.compute.agents.util.kafka.KafkaMasterAgent
import ai.beyond.compute.sharded.ShardedMessages
import akka.actor.{Actor, Props}
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._
import java.time.Instant

import ai.beyond.compute.logging.AiraSampleOneAgentLogging

object AiraSampleOneAgent extends ShardedMessages {

  def props(agentId: String) = Props(new AiraSampleOneAgent)

  // Create the catch Message type for this agent
  // This will allows us to determine which shard manager
  // to forward messages to. Refer to the ShardedAgents.receive
  // function to see how its used, AlgorithmAgent.Message
  trait Message extends ShardedMessage


  ///////////////////////
  // Messages specific to the AiraSampleOne Agent
  ///////////////////////
  // Sample help messages
  case class PrintPath(id: String) extends Message
  case class HelloThere(id: String, msgBody: String) extends Message
  ///////////////////////


  ///////////////////////
  // Private Read-Only Parameters

  // Default timeout for Ask patterns to other agents (even self)
  // Implicit so it can just be used where necessary
  private implicit val TIMEOUT: Timeout = Timeout(5 seconds)
  ///////////////////////
}

class AiraSampleOneAgent extends Actor with AiraSampleOneAgentLogging {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._
  // Import the companion object above to use the messages defined for us
  import AiraSampleOneAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def agentPath: String = self.path.toStringWithoutAddress
  def agentName: String = self.path.name

  // Get a reference to the helper agents. This should be updated in the
  // the agent lifecycle methods. TODO: Before using the ref maybe check if valid
  var mongoMasterAgentRef = actorSelection("/user/" + MongoMasterAgent.name)
  var kafkaMasterAgentRef = actorSelection("/user/" + KafkaMasterAgent.name)



  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("AiraSampleOne Agent - {} - starting", agentPath)

    // Get reference to helper agents
    mongoMasterAgentRef = actorSelection("/user/" + MongoMasterAgent.name)
    kafkaMasterAgentRef = actorSelection("/user/" + KafkaMasterAgent.name)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "AiraSampleOne Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)

    // Get reference to helper agents
    mongoMasterAgentRef = actorSelection("/user/" + MongoMasterAgent.name)
    kafkaMasterAgentRef = actorSelection("/user/" + KafkaMasterAgent.name)
  }

  override def postStop(): Unit = {
    log.info("AiraSampleOne Agent - {} - stopped", agentPath)
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//



  //------------------------------------------------------------------------//
  // Begin Actor Receive Behavior
  //------------------------------------------------------------------------//
  override def receive: Receive = idle // Set the initial behavior to idle

  def idle: Receive = {
    case PrintPath(id) =>
      log.info("My, [{}], path is {}", id, agentPath)

    case HelloThere(id, msgBody) =>
      log.info("Hello there, [{}], you said, '{}'", id, msgBody)
  }
  //------------------------------------------------------------------------//
  // End Actor Receive Behavior
  //------------------------------------------------------------------------//

}
