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
import java.time.Instant

import ai.beyond.fpt.mvp.compute.agents.db.MongoDbAgent
import ai.beyond.fpt.mvp.compute.agents.db.MongoDbAgent.ComputeJobMetaData
import ai.beyond.fpt.mvp.compute.agents.kafka.{KafkaMasterAgent, KafkaProducerAgent}

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
  // Ask based Messages, usually in pairs to highlight question/answer pattern with diff message loads
  case class GetState(id: String) extends Message
  case class State(id: String, state: String, percentComplete: Int, lastUpdated: Long) extends Message

  // Tell based Messages, think of these as commands
  case class CancelJob(id: String) extends Message
  case class CompleteJob(id: String) extends Message
  // InitiateCompute is a special case, even though it sounds like a Tell message, it still replies
  // back to the sender with a State message. This is to inform the caller that initiate was successful
  case class InitiateCompute(id: String, name: String, owner: String, socketeer: String) extends Message

  // Sample help messages
  case class PrintPath(id: String) extends Message
  case class HelloThere(id: String, msgBody: String) extends Message
  ///////////////////////


  ///////////////////////
  // Private Read-Only Parameters
  private val TOPIC_JOBSTATUS: String = "job_status"

  // Default timeout for Ask patterns to other agents (even self)
  // Implicit so it can just be used where necessary
  private implicit val TIMEOUT = Timeout(5 seconds)
  ///////////////////////
}

// Collect json format instances into a support trait
// Helps marshall the messages between JSON received via HTTP APIs
trait ComputeAgentJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  // Add any messages that you need to be marshalled back and forth from/to json
  implicit val itemFormat = jsonFormat4(ComputeAgent.InitiateCompute)
  implicit val stateFormat = jsonFormat4(ComputeAgent.State)
}

class ComputeAgent extends Actor with ComputeAgentLogging with ComputeAgentJsonSupport {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._
  // Import the companion object above to use the messages defined for us
  import ComputeAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def agentPath: String = self.path.toStringWithoutAddress
  def agentName: String = self.path.name

  // Get a reference to the helper agents. This should be updated in the
  // the agent lifecycle methods. TODO: Before using the ref maybe check if valid
  var mongoDbAgentRef = actorSelection("/user/" + MongoDbAgent.name)
  var kafkaMasterAgentRef = actorSelection("/user/" + KafkaMasterAgent.name)

  ///////////////////////
  // Meta property object to store any meta data
  object META_PROPS {
    var name: String = ""
    var owner: String = ""
    var percentComplete: Int = 0 // 0-100
    var lastKnownUpdate: Long = 0 // UNIX Timestamp
    var socketeer: String = "" // socketeer
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

    // Get reference to helper agents
    mongoDbAgentRef = actorSelection("/user/" + MongoDbAgent.name)
    kafkaMasterAgentRef = actorSelection("/user/" + KafkaMasterAgent.name)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "Compute Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)

    // Get reference to helper agents
    mongoDbAgentRef = actorSelection("/user/" + MongoDbAgent.name)
    kafkaMasterAgentRef = actorSelection("/user/" + KafkaMasterAgent.name)
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
      sender ! State(id, "Idle", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)

    case PrintPath(id) =>
      log.info("My, [{}], path is {}", id, agentPath)

    case HelloThere(id, msgBody) =>
      log.info("Hello there, [{}], you said, '{}'", id, msgBody)

    case InitiateCompute(id, name, owner, socketeer) =>
      log.info("Initiating compute job with ID[{}]", id)
      // Store any meta data properties that were given
      META_PROPS.name = name
      META_PROPS.owner = owner
      META_PROPS.socketeer = socketeer
      // Store these metadata into Mongo
      mongoDbAgentRef ! ComputeJobMetaData(id, name, owner, socketeer)

      sender ! State(id, "Initiating", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)

      runCompute(id) // Start the compute

      become(computing)
  }

  // Compute behavior state
  def computing: Receive = {
    case GetState(id) =>
      log.info("I, [{}], have been computing tirelessly", id)
      sender ! State(id, "Running", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)

    case CompleteJob(id) =>
      log.info("Finalizing the Compute Job [{}] and marking completion", id)
      computeJob.cancel()

      become(completed)

    case CancelJob(id) =>
      log.info("Cancelling the Compute Job [{}]", id)
      computeJob.cancel()

      become(cancelled)
  }

  // Cancelled behavior state
  def cancelled: Receive = {
    case GetState(id) =>
      log.info("I, [{}], have been cancelled", id)
      sender ! State(id, "Cancelled", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)
  }

  // Completed behavior state
  def completed: Receive = {
    case GetState(id) =>
      log.info("I, [{}], completed my task", id)
      sender ! State(id, "Completed", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)
  }
  //------------------------------------------------------------------------//
  // End Actor Receive Behavior
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Begin Compute Functions
  //------------------------------------------------------------------------//
  def runCompute(id: String): Unit = {

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
      this.META_PROPS.lastKnownUpdate = Instant.now().getEpochSecond

      // Ask yourself what state you are in, since this is an ASK, we need to Await Result
      val future = self ? GetState(id) // GetState returns back a State message
      val state = Await.result(future, TIMEOUT.duration).asInstanceOf[State]

      // Create new json to send over kafka
      val json = JsObject(
        "id" -> JsString(id),
        "state" -> JsString(state.state),
        "socketeer" -> JsString(META_PROPS.socketeer),
        "percentComplete" -> JsNumber(state.percentComplete))

      // Send the kafka master agent a message to send over kafka via its producer agent children
      kafkaMasterAgentRef ! KafkaProducerAgent.Message(TOPIC_JOBSTATUS, id, json.toString())
    }
    /////////////////////////////////////////////////////////////////////////

  }
  //------------------------------------------------------------------------//
  // End Compute Functions
  //------------------------------------------------------------------------//

}
