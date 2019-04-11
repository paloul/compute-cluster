package ai.beyond.compute.agents.aira.geo

import java.time.Instant

import ai.beyond.compute.agents.aira.AiraAgent
import ai.beyond.compute.agents.util.db.MongoMasterAgent
import ai.beyond.compute.agents.util.kafka.KafkaMasterAgent
import ai.beyond.compute.sharded.ShardedMessages
import akka.actor.{Cancellable, Props}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.pattern.ask
import akka.util.Timeout
import spray.json.DefaultJsonProtocol

import scala.concurrent.Await
import scala.concurrent.duration._

object GeoDynamicAgent extends ShardedMessages {
  def props(agentId: String) = Props(new GeoDynamicAgent)

  // Create the catch Message type for this agent
  // This will allows us to determine which shard manager
  // to forward messages to. Refer to the ShardedAgents.receive
  // function to see how its used, AlgorithmAgent.Message
  trait Message extends ShardedMessage

  ///////////////////////
  // Messages specific to the GeoDynamic Agent
  ///////////////////////
  // Ask based Messages
  case class GetState(id: String) extends Message
  case class State(id: String, state: String, percentComplete: Int, lastUpdated: Long) extends Message

  // Tell-based messages
  case class Start(id: String, prodPath: String, owcPath: String, faultPath: String, perfPath: String,
                   trajPath: String, geoMeanPath: String) extends Message
  case class CancelJob(id: String) extends Message
  case class CompleteJob(id: String) extends Message

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

// Collect json format instances into a support trait
// Helps marshall the messages between JSON received via HTTP APIs
trait GeoDynamicAgentJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  // Add any messages that you need to be marshalled back and forth from/to json
  implicit val itemFormat = jsonFormat7(GeoDynamicAgent.Start)
  implicit val stateFormat = jsonFormat4(GeoDynamicAgent.State)
}

class GeoDynamicAgent extends AiraAgent with GeoDynamicAgentJsonSupport {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._
  // Import the companion object above to use the messages defined for us
  import GeoDynamicAgent._

  // Meta property object to store any meta data
  object META_PROPS {
    var percentComplete: Int = 0 // 0-100
    var lastKnownUpdate: Long = 0 // UNIX Timestamp
  }

  // Temporary Cancellable future connected to the underlying compute job mimic block
  // See below - in the runCompute function
  var computeJob: Cancellable = _


  //------------------------------------------------------------------------//
  // Begin Actor Receive Behavior
  //------------------------------------------------------------------------//
  override def receive: Receive = idle // Set the initial behavior to idle

  // Idle behavior state
  def idle: Receive = {
    case GetState(id) =>
      log.info("ID[{}] is in an idle state", id)
      sender ! State(id, "Idle", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)

    case PrintPath(id) =>
      log.info("My, [{}], path is {}", id, agentPath)

    case HelloThere(id, msgBody) =>
      log.info("Hello there, [{}], you said, '{}'", id, msgBody)

    case Start(id, prodPath, owcPath, faultPath, perfPath, trajPath, geoMeanPath) =>
      log.info("Starting compute with job ID[{}]", id)
      sender ! State(id, "Starting", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)
      runCompute(id, prodPath, owcPath, faultPath, perfPath, trajPath, geoMeanPath)
      become(computing)
  }

  // Computing behavior state
  def computing: Receive = {
    case GetState(id) =>
      log.info("ID[{}] is currently computing", id)
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
      log.info("ID[{}] has been cancelled", id)
      sender ! State(id, "Cancelled", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)
  }

  // Completed behavior state
  def completed: Receive = {
    case GetState(id) =>
      log.info("ID[{}] has completed", id)
      sender ! State(id, "Completed", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)
  }

  //
  //------------------------------------------------------------------------//
  // End Actor Receive Behavior
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Begin Compute Functions
  //------------------------------------------------------------------------//
  def runCompute(id: String, prodPath: String, owcPath: String, faultPath: String, perfPath: String,
                     trajPath: String, geoMeanPath: String): Unit = {
    // TODO: Load the files and do stuff
    log.info("File paths (1/2): {}, {}, {}", prodPath, owcPath, faultPath)
    log.info("File paths (2/2): {}, {}, {}", perfPath, trajPath, geoMeanPath)

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
      /*val json = JsObject(
        "id" -> JsString(id),
        "state" -> JsString(state.state),
        "socketeer" -> JsString(META_PROPS.socketeer),
        "percentComplete" -> JsNumber(state.percentComplete))

      // Send the kafka master agent a message to send over kafka via its producer agent children
      kafkaMasterAgentRef ! KafkaProducerAgent.Message(TOPIC_JOBSTATUS, id, json.toString())*/
    }
    /////////////////////////////////////////////////////////////////////////

  }

}
