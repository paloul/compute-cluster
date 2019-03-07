package ai.beyond.fpt.mvp.compute.agents

import ai.beyond.fpt.mvp.compute.logging.ComputeAgentLogging
import ai.beyond.fpt.mvp.compute.sharded.ShardedMessages
import akka.actor.{Actor, Cancellable, Props}
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.Await
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

  ///////////////////////
  // Messages specific to the Compute Agent
  ///////////////////////
  // Ask based Messages
  case class GetState(id: String) extends Message
  case class State(id: String, state: String, percentComplete: Int) extends Message

  // Tell based Messages
  case class CancelJob(id: String) extends Message
  case class CompleteJob(id: String) extends Message
  case class InitiateCompute(id: String, partition: Int, socketeer: String) extends Message

  // Sample help messages
  case class PrintPath(id: String) extends Message
  case class HelloThere(id: String, msgBody: String) extends Message
  ///////////////////////

  ///////////////////////
  // Private Read-Only Parameters
  private val TOPIC_JOBSTATUS: String = "job_status"

  // Default timeout for Ask patterns to other agents (even self)
  private implicit val TIMEOUT = Timeout(5 seconds)
  ///////////////////////
}

// Collect json format instances into a support trait
// Helps marshall the messages between JSON received via HTTP APIs
trait ComputeAgentJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  // Add any messages that you need to be marshalled back and forth from/to json
  implicit val itemFormat = jsonFormat3(ComputeAgent.InitiateCompute)
  implicit val stateFormat = jsonFormat3(ComputeAgent.State)
}

class ComputeAgent extends Actor with ComputeAgentLogging with ComputeAgentJsonSupport {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._
  // Import the companion object above to use the messages defined for us
  import ComputeAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def agentPath: String = self.path.toStringWithoutAddress
  def agentName: String = self.path.name


  ///////////////////////
  // Meta property object to store any meta data
  object META_PROPS {
    var percentComplete: Int = 0 // 0-100
  }
  ///////////////////////


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
    case GetState(id) =>
      log.info("I, [{}], am in an Idle state of mind", id)
      sender ! State(id, "Idle", META_PROPS.percentComplete)

    case PrintPath(id) =>
      log.info("My, [{}], path is {}", id, agentPath)

    case HelloThere(id, msgBody) =>
      log.info("Hello there, [{}], you said, '{}'", id, msgBody)

    case InitiateCompute(id, partition, socketeer) =>
      log.info("Initiating compute job with ID[{}]", id)
      runCompute(id, partition, socketeer)

      become(computing)
  }

  // Compute behavior state
  def computing: Receive = {
    case GetState(id) =>
      log.info("I, [{}], have been computing tirelessly", id)
      sender ! State(id, "Running", META_PROPS.percentComplete)

    case CompleteJob(id) =>
      log.info("Finalizing the Compute Job [{}] and marking completion", id)
      computeJob.cancel()

      become(completed)

    case CancelJob(id) =>
      log.info("Cancelling the Compute Job [{}]", id)
      computeJob.cancel()

      become(cancelled)
  }

  def cancelled: Receive = {
    case GetState(id) =>
      log.info("I, [{}], have been cancelled", id)
      sender ! State(id, "Cancelled", META_PROPS.percentComplete)
  }

  def completed: Receive = {
    case GetState(id) =>
      log.info("I, [{}], completed my task", id)
      sender ! State(id, "Completed", META_PROPS.percentComplete)
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
    //  After 30-80 messages sent it will send itself a CompleteJob message which will cancel the Scheduler
    //  and mark completion of the simulated job
    var messageCount: Int = 0
    def roundUp(f: Float) = math.ceil(f).toInt
    val totalMessages:Float = 30 + (new scala.util.Random).nextInt((100 - 30) + 1) // random num between 30 and 100
    computeJob = system.scheduler.schedule(250 milliseconds, 1000 milliseconds) {

      if (messageCount >= totalMessages) self ! CompleteJob(id)

      messageCount = messageCount + 1 // Increment messages sent

      // Calculate percentage complete based on messages sent and randomly chosen total num of messages to send
      // This basically simulates the amount of work that needs to be done
      this.META_PROPS.percentComplete = Math.min(roundUp((messageCount / totalMessages) * 100), 100)

      // Ask yourself what state you are in, since this is an ASK, we need to Await Result
      val future = self ? GetState(id) // GetState returns back a State message
      val state = Await.result(future, TIMEOUT.duration).asInstanceOf[State]

      // Create new json to send over kafka
      val json = JsObject(
        "id" -> JsString(id),
        "state" -> JsString(state.state),
        "socketeer" -> JsString(socketeer),
        "percentComplete" -> JsNumber(state.percentComplete))

      kafkaProducerAgentRef ! KafkaProducerAgent.Message(TOPIC_JOBSTATUS, partition, id, json.toString())
    }
    /////////////////////////////////////////////////////////////////////////

  }
  //------------------------------------------------------------------------//
  // End Compute Functions
  //------------------------------------------------------------------------//

}
