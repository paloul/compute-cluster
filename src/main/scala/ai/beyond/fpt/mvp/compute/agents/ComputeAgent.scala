package ai.beyond.fpt.mvp.compute.agents

import ai.beyond.fpt.mvp.compute.logging.ComputeAgentLogging
import ai.beyond.fpt.mvp.compute.sharded.ShardedMessages
import akka.actor.{Actor, Cancellable, Props}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

// The companion object that extends the base ShardedMessages trait
// Inherits ShardedMessages so that the 1) underlying extractId/Shard
// functions can apply, 2) the basic Stop message is inherited,
// 3) the trait ShardMessage is mixed in so that we can create the
// general Message type for this specific agent type and used in
// routing from managers to ShardRegions to unique intended entity agents
object ComputeAgent extends ShardedMessages {
  def props(agentId: String) = Props(new ComputeAgent)

  // Create the catch Message type for this agent
  // This will allows us to determine which shard manager
  // to forward messages to. Refer to the ShardedAgents.receive
  // function to see how its used, AlgorithmAgent.Message
  trait Message extends ShardedMessage

  // Messages specific to the StockPriceAgent
  case class CancelJob(agentId: String) extends Message
  case class PrintPath(agentId: String) extends Message
  case class HelloThere(agentId: String, msgBody: String) extends Message
  case class InitiateCompute(agentId: String, topic: String, partition: Int, socketeer: String)
}

class ComputeAgent extends Actor with ComputeAgentLogging {
  // Import the companion object above to use the messages defined for us
  import ComputeAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name

  var computeJob: Cancellable = null


  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("Compute Agent - {} - starting", id)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "Compute Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("Compute Agent - {} - stopped", id)
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Begin Actor Receive Behavior
  //------------------------------------------------------------------------//
  override def receive: Receive = {
    case PrintPath(agentId) =>
      log.info("I've been told to print my path: {}", agentId)
      context.actorSelection("/user/" + KafkaProducerAgent.name) ! KafkaProducerAgent.Message("hello", 0, "Compute", "PrintPath!")

    case HelloThere(agentId, msgBody) =>
      log.info("({}) Hello there, you said, '{}'", agentId, msgBody)
      runCompute("hello", 0, "wassup")

    case InitiateCompute(agentId, topic, partition, socketeer) =>
      log.info("Initiating compute job with ID:{}", agentId)
      runCompute(topic, partition, socketeer)

    case CancelJob(agentId) =>
      log.info("Cancelling the Compute Job {}", agentId)
      computeJob.cancel()
  }
  //------------------------------------------------------------------------//
  // End Actor Receive Behavior
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Begin Compute Functions
  //------------------------------------------------------------------------//
  def runCompute(topic: String , partition: Int, socketeer: String): Unit = {

    val kafkaProducerAgentRef = context.actorSelection("/user/" + KafkaProducerAgent.name)

    var messageCount = 0
    computeJob = context.system.scheduler.schedule(500 milliseconds, 2000 milliseconds) {

      // TODO: Create the JSON message here and convert to string for msg
      val msg = "awesomeness"
      kafkaProducerAgentRef ! KafkaProducerAgent.Message(topic, partition, id, msg)

      messageCount += 1
      if (messageCount > 10) self ! CancelJob(id)

    }

  }
  //------------------------------------------------------------------------//
  // End Compute Functions
  //------------------------------------------------------------------------//

}
