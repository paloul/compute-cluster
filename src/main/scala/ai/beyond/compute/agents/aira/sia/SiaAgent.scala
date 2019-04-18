package ai.beyond.compute.agents.aira.sia

import ai.beyond.compute.agents.aira.AiraAgent
import ai.beyond.compute.sharded.{ShardedAgents, ShardedMessages}
import akka.actor.Props
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.util.Timeout
import org.apache.spark.sql.Encoders
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._

import org.apache.spark.mllib.linalg.{Matrix, Matrices}

object SiaAgent extends ShardedMessages {
  def props(agentId: String) = Props(new SiaAgent)

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
  case class Start(id: String, voiDimensions: List[Int],
                   resVoiFileName: String, faultVoiFileName: String) extends Message
  case class CancelJob(id: String) extends Message
  case class CompleteJob(id: String) extends Message

  // Sample help messages
  case class PrintPath(id: String) extends Message
  case class HelloThere(id: String, msgBody: String) extends Message
  ///////////////////////


  ///////////////////////
  // Private Read-Only Parameters
  ///////////////////////

  // Default timeout for Ask patterns to other agents (even self)
  // Implicit so it can just be used where necessary
  private implicit val TIMEOUT: Timeout = Timeout(5 seconds)

  ///////////////////////


  ///////////////////////
  // Data Type Schema Specifications
  ///////////////////////
  //,x,y,z,nx,ny,nz,Porosity,PermX,PermZ,BulkVolume,PermY,Zones
  case class VoiRes(i: Int, x: Float, y: Float, z: Float,
                    nx: Float, ny: Float, nz: Float, porosity: Float,
                    permeabilityX: Float, permeabilityZ: Float, bulkVolume: Float,
                    permeabilityY: Float, zones: Float)
  private val VOI_RES_SCHEMA = Encoders.product[VoiRes].schema

  //,x,y,z,nx,ny,nz,Index,OrigName
  case class VoiFault(i: Int, x: Float, y: Float, z: Float,
                    nx: Float, ny: Float, nz: Float, index: Float, origName: Int)
  private val VOI_FAULT_SCHEMA = Encoders.product[VoiFault].schema

  ///////////////////////
}

// Collect json format instances into a support trait
// Helps marshall the messages between JSON received via HTTP APIs
trait SiaAgentJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  // Add any messages that you need to be marshalled back and forth from/to json
  implicit val startFormat = jsonFormat4(SiaAgent.Start)
  implicit val stateFormat = jsonFormat4(SiaAgent.State)
}

/**
  * The Agent controlling the Sia processing logic. Makes heavy use of Spark clusters (*SHOULD*)
  */
class SiaAgent extends AiraAgent {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._
  // Import the companion object above to use the messages defined for us
  import SiaAgent._
  // Import implicits specific to spark session
  import spark.implicits._

  // Constants
  val HDFS_BASE: String = ShardedAgents.mySettings.get.spark.hdfsBase

  // Meta property object to store any meta data
  object META_PROPS {
    var voiDimensionsX: Int = 0
    var voiDimensionsY: Int = 0
    var voiDimensionsZ: Int = 0
    var percentComplete: Int = 0 // 0-100
    var lastKnownUpdate: Long = 0 // UNIX Timestamp
  }




  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    super.preStart()

    log.info("Sia Agent - {} - starting", agentPath)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "Sia Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))

    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    super.postStop()

    log.info("Sia Agent - {} - stopped", agentPath)
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
      log.info("ID[{}] is in an idle state", id)
      sender ! State(id, "Idle", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)

    case PrintPath(id) =>
      log.info("My, [{}], path is {}", id, agentPath)

    case HelloThere(id, msgBody) =>
      log.info("Hello there, [{}], you said, '{}'", id, msgBody)

    case Start(id, voiDimensions, resVoiFileName, faultVoiFileName) =>
      log.info("Starting compute with job ID[{}]", id)

      // voi dimensions should always be length 3
      // TODO: Have a check in place to verify we have 3 items for X, Y, Z
      META_PROPS.voiDimensionsX = voiDimensions(0)
      META_PROPS.voiDimensionsY = voiDimensions(1)
      META_PROPS.voiDimensionsZ = voiDimensions(2)

      sender ! State(id, "Starting", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)
      startProcessing(id, resVoiFileName, faultVoiFileName)
      become(computing)
  }

  // Computing behavior state
  def computing: Receive = {
    case GetState(id) =>
      log.info("ID[{}] is currently computing", id)
      sender ! State(id, "Running", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)

    case CompleteJob(id) =>
      log.info("Finalizing the Compute Job [{}] and marking completion", id)
      become(completed)

    case CancelJob(id) =>
      log.info("Cancelling the Compute Job [{}]", id)
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




  /**
    * Start Sia processing starting with the raw data files
    * @param id
    * @param voiResFileName
    * @param voiFaultFileName
    */
  def startProcessing(id: String, voiResFileName: String, voiFaultFileName: String): Unit = {

    log.info("File paths: {}, {}", voiResFileName, voiFaultFileName)

    // Load the res voi file
    val voiResDs = time (
      "Reading file ["+HDFS_BASE+voiResFileName+"]", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(VOI_RES_SCHEMA)
          .load(HDFS_BASE + voiResFileName)
          .as[VoiRes].cache() // cache so that we can build out the 3D matrices and not read each time
      }
    )
    voiResDs.printSchema()
    voiResDs.show(5)
    log.info("VOI Res Data Set Length: [{}]", voiResDs.count())

    // Load the res fault file
    val voiFaultDs = time (
      "Reading file ["+HDFS_BASE+voiFaultFileName+"]", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(VOI_FAULT_SCHEMA)
          .load(HDFS_BASE + voiFaultFileName)
          .as[VoiFault].cache() // cache so that we can build out the 3D matrices and not read each time
      }
    )
    voiFaultDs.printSchema()
    voiFaultDs.show(5)
    log.info("VOI Fault Data Set Length: [{}]", voiFaultDs.count())

    

  }

}
