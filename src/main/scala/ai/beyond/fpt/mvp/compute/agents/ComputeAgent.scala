package ai.beyond.fpt.mvp.compute.agents

import ai.beyond.fpt.mvp.compute.logging.ComputeAgentLogging
import ai.beyond.fpt.mvp.compute.sharded.ShardedMessages
import akka.actor.{Actor, Cancellable, Props}

import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport

import scala.concurrent.duration._

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
  case class CancelJob(id: String) extends Message
  case class PrintPath(id: String) extends Message
  case class HelloThere(id: String, msgBody: String) extends Message
  case class InitiateCompute(id: String, partition: Int, socketeer: String) extends Message

  ///////////////////////
  // Private Read-Only Parameters
  private val TOPIC_JOBSTATUS: String = "job_status"
  ///////////////////////
}

// Collect json format instances into a support trait
// Helps marshall the messages between JSON received via HTTP APIs
trait ComputeAgentJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val itemFormat = jsonFormat3(ComputeAgent.InitiateCompute)
}

class ComputeAgent extends Actor with ComputeAgentLogging with ComputeAgentJsonSupport {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._
  // Import the companion object above to use the messages defined for us
  import ComputeAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def agentId: String = self.path.name

  /////////////////////////////////////////////////////////////////////////
  // FIXME: GKP - 2019-02-27
  // Temporary Cancellable future connected to the underlying compute job mimic block
  // See below - in the runCompute function
  var computeJob: Cancellable = null
  /////////////////////////////////////////////////////////////////////////


  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("Compute Agent - {} - starting", agentId)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "Compute Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("Compute Agent - {} - stopped", agentId)
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
    case PrintPath(id) =>
      log.info("I've been told to print my path: {}", id)

    case HelloThere(id, msgBody) =>
      log.info("({}) Hello there, you said, '{}'", id, msgBody)

    case InitiateCompute(id, partition, socketeer) =>
      log.info("Initiating compute job with ID:{}", id)
      runCompute(id, partition, socketeer)

      become(computing)
  }

  // Compute behavior state
  def computing: Receive = {
    case CancelJob(id) =>
      log.info("Cancelling the Compute Job {}", id)
      computeJob.cancel()

      become(idle)
  }
  //------------------------------------------------------------------------//
  // End Actor Receive Behavior
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Begin Compute Functions
  //------------------------------------------------------------------------//
  def runCompute(id: String, partition: Int, socketeer: String): Unit = {

    val kafkaProducerAgentRef = actorSelection("/user/" + KafkaProducerAgent.name)

    /////////////////////////////////////////////////////////////////////////
    // FIXME: GKP - 2019-02-27
    //  This block just mimics potential compute being done
    //  It sets up a scheduler that sends out status messages over kafka using the KafkaProducer Agent
    //  After 30-80 messages sent it will send itself a CancelJob message which will cancel the Scheduler
    var messageCount: Int = 0
    def roundUp(f: Float) = math.ceil(f).toInt
    val totalMessages:Float = 30 + (new scala.util.Random).nextInt((80 - 30) + 1) // random num between 30 and 80
    computeJob = system.scheduler.schedule(500 milliseconds, 1000 milliseconds) {

      messageCount = messageCount + 1
      val percentComplete: Int = roundUp((messageCount / totalMessages) * 100)
      log.info("Job [{}] Complete - {}%", id, percentComplete)

      // Create new json to send over kafka
      val json = JsObject(
        "id" -> JsString(id),
        "socketeer" -> JsString(socketeer),
        "percent_done" -> JsNumber(percentComplete))

      kafkaProducerAgentRef ! KafkaProducerAgent.Message(TOPIC_JOBSTATUS, partition, id, json.toString())

      if (messageCount == totalMessages) self ! CancelJob(id)
    }
    /////////////////////////////////////////////////////////////////////////

  }
  //------------------------------------------------------------------------//
  // End Compute Functions
  //------------------------------------------------------------------------//

}
