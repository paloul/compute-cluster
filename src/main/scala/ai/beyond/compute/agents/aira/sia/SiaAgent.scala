package ai.beyond.compute.agents.aira.sia

import java.io.File

import ai.beyond.compute.agents.aira.AiraAgent
import ai.beyond.compute.sharded.{ShardedAgents, ShardedMessages}
import akka.actor.Props
import akka.cluster.sharding.ShardRegion.Passivate
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import spray.json.DefaultJsonProtocol
import java.time.Instant
import java.util.concurrent.Executors

import concurrent.ExecutionContext
import kantan.csv._
import kantan.csv.ops._
import org.nd4j.linalg.factory.Nd4j

object SiaAgent extends ShardedMessages {
  def props(agentId: String) = Props(new SiaAgent)

  // Execution Pool for Processing Data, these allow agents to perform long-running
  // tasks on a different thread pool separate from main message handler
  private val procExecutorService = Executors.newFixedThreadPool(6 )
  private val procExecutionContext = ExecutionContext.fromExecutorService(procExecutorService)

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
  case class State(id: String, state: String, metaProps: MetaProps) extends Message

  // Tell-based messages
  case class Start(id: String, voiDimensions: List[Int],
                   voiResFileName: String, voiFaultFileName: String) extends Message
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
  final case class VoiRes(i: Int, x: Float, y: Float, z: Float,
                    nx: Int, ny: Int, nz: Int, porosity: Float,
                    permX: Float, permZ: Float, bulkVolume: Float,
                    permY: Float, zones: Float)
  implicit val voiResDecoder: RowDecoder[VoiRes] =
    RowDecoder.ordered { (i: Int, x: Float, y: Float, z: Float,
                          nx: Float, ny: Float, nz: Float, porosity: Float,
                          permX: Float, permZ: Float, bulkVolume: Float,
                          permY: Float, zones: Float) =>
      // nx, ny, nz and zones are floats in the data, but VoiRes needs to represent them
      // as integers because they are used for indexing and zones
    new VoiRes(i, x, y, z, nx.toInt, ny.toInt, nz.toInt, porosity, permX, permZ, bulkVolume, permY, zones.toInt)
  }

  //,x,y,z,nx,ny,nz,Index,OrigName
  final case class VoiFault(i: Int, x: Float, y: Float, z: Float,
                    nx: Int, ny: Int, nz: Int, index: Int, origName: String)
  implicit val voiFaultDecoder: RowDecoder[VoiFault] =
    RowDecoder.ordered { (i: Int, x: Float, y: Float, z: Float,
                          nx: Float, ny: Float, nz: Float, index: Float, origName: String) =>
      new VoiFault(i, x, y, z, nx.toInt, ny.toInt, nz.toInt, index.toInt, origName)
    }
  ///////////////////////


  ///////////////////////
  // Supporting Data Types
  ///////////////////////
  // Used to hold metadata properties for each Sia job
  final case class MetaProps(var voiDimX: Int = 0, var voiDimY: Int = 0, var voiDimZ: Int = 0,
                             var voiResFileName: String = "", var voiFaultFileName: String = "",
                             var percentComplete: Int = 0, var lastKnownStage: String = "", var lastKnownUpdate: Long = 0)
  ///////////////////////
}

// Collect json format instances into a support trait
// Helps marshall the messages between JSON received via HTTP APIs
trait SiaAgentJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  // Add any messages that you need to be marshalled back and forth from/to json
  implicit val metaPropsFormat = jsonFormat8(SiaAgent.MetaProps) // First because used by stateFormat
  implicit val startFormat = jsonFormat4(SiaAgent.Start)
  implicit val stateFormat = jsonFormat3(SiaAgent.State)
}

/**
  * The Agent controlling the Sia processing logic. Makes heavy use of Spark clusters (*SHOULD*)
  */
class SiaAgent extends AiraAgent  {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._
  // Import the companion object above to use the messages defined for us
  import SiaAgent._

  // Meta property object to store any meta data
  var META_PROPS = MetaProps()

  // Constants
  val BASE_FILE_PATH: String = ShardedAgents.mySettings.get.sia.files.basePath



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
      sender ! State(id, "Idle", META_PROPS)

    case PrintPath(id) =>
      log.info("My, [{}], path is {}", id, agentPath)

    case HelloThere(id, msgBody) =>
      log.info("Hello there, [{}], you said, '{}'", id, msgBody)

    case Start(id, voiDimensions, voiResFileName, voiFaultFileName) =>
      log.info("Starting compute with job ID[{}]", id)

      // Store the items we received to start processing in META_PROPS
      META_PROPS.voiResFileName = voiResFileName
      META_PROPS.voiFaultFileName = voiFaultFileName
      // voi dimensions should always be length 3
      META_PROPS.voiDimX = voiDimensions(0)
      META_PROPS.voiDimY = voiDimensions(1)
      META_PROPS.voiDimZ = voiDimensions(2)

      // TODO: Verify all the provided parameters are valid, i.e. data types/lengths and files are found

      // Reply back to sender that we are officially starting, if any errors were come across
      // with the provided parameters, i.e types or file not found, this is where you would
      // notify the sender of what went wrong.
      sender ! State(id, "Starting", META_PROPS)

      // Provided parameters were ok at this point, let's start processing
      startProcessing(id)
  }

  // Computing behavior state
  def running: Receive = {
    case GetState(id) =>
      log.info("ID[{}] is currently running", id)
      sender ! State(id, "Running", META_PROPS)

    case CompleteJob(id) =>
      log.info("Finalizing the Sia Job [{}] and marking completion", id)
      // TODO: Mark completion, whatever that means
      become(completed)

    case CancelJob(id) =>
      log.info("Cancelling the Sia Job [{}]", id)
      // TODO: Mark cancellation and stop underlying long running tasks
      become(cancelled)
  }

  // Cancelled behavior state
  def cancelled: Receive = {
    case GetState(id) =>
      log.info("ID[{}] has been cancelled", id)
      sender ! State(id, "Cancelled", META_PROPS)
  }

  // Completed behavior state
  def completed: Receive = {
    case GetState(id) =>
      log.info("ID[{}] has completed", id)
      sender ! State(id, "Completed", META_PROPS)
  }
  //
  //------------------------------------------------------------------------//
  // End Actor Receive Behavior
  //------------------------------------------------------------------------//




  //------------------------------------------------------------------------//
  // Helper Future Wrapped Functions
  //------------------------------------------------------------------------//
  /**
    * Helper function to start long running data processing with an Execution Context
    * in possession of a separate thread pool.
    * @param id
    * @return A Future with MetaProps about the job
    */
  def startProcessing(id: String): Future[MetaProps] = Future {
    // Change our behavior state to running in order to treat incoming messages differently
    //become(running) // FIXME: uncomment this for production, commented out to help testing (stay in state)

    META_PROPS.lastKnownStage = "startProcessing(id: String)"
    META_PROPS.lastKnownUpdate = Instant.now().getEpochSecond

    // Here we can start our long running tasks, this function
    // should be running in its own execution context. Have fun with
    // background processes, as they will not stop regular agent functionality
    val reservoirMatrix = time("Read/Process VOI files and populate matrix", { readFilesGenerateMatrix() })

    META_PROPS // Return the META_PROPS instance that we store metadata about Sia jobs

  }(procExecutionContext)
  //------------------------------------------------------------------------//
  // End Helper Future Wrapped Functions
  //------------------------------------------------------------------------//




  //------------------------------------------------------------------------//
  // Private Processing Functions
  //------------------------------------------------------------------------//

  /**
    * readFilesGenerateMatrix
    * @return Four-Dimensional INDArray from ND4J that represents the reservoir
    *         in 3D with the properties stored in the 4th dimension
    */
  private def readFilesGenerateMatrix() = {

    META_PROPS.lastKnownStage = "readFilesGenerateMatrix()"
    META_PROPS.lastKnownUpdate = Instant.now().getEpochSecond

    // main reservoir matrix that holds our data, 4-dimensional array with the 4th dimension holding values
    // The 4th dimension is an array holding properties of reservoirs coming from raw data.
    // NOTE: 2019-04-26 - The 4th dimension is only of length 1 for now as we only use Permeability X
    val reservoirMatrix = Nd4j.zeros(META_PROPS.voiDimX, META_PROPS.voiDimY, META_PROPS.voiDimZ, 1)

    // Create a buffered source to the voi res file, we do this because there is no need to load
    // the entire file into memory. We go line by line and create the data structure, a 3D Matrix,
    // with the raw data and then discard the raw data.
    // Iterate over huge CSV files this way without loading more than one row at a time in memory.
    val voiResFile = new File(BASE_FILE_PATH + META_PROPS.voiResFileName)
    val voiResIterator = voiResFile.asCsvReader[VoiRes](rfc.withHeader)

    //val voiFaultFile = new File(BASE_FILE_PATH + META_PROPS.voiFaultFileName)
    //val voiFaultIterator = voiFaultFile.asCsvReader[VoiFault](rfc.withHeader)

    // Read Result is of type Either - https://www.scala-lang.org/api/current/scala/util/Either.html
    voiResIterator.foreach( readResult => {
      readResult match {
        // Right side of read result is our actual value, IF everything went well reading it
        case Right(voiRes) => {
          reservoirMatrix.putScalar(Array(voiRes.nx, voiRes.ny, voiRes.nz, 0), voiRes.permX)
        }
        // Left side of read result is the error, IF something went bad
        case Left(error) => {
          log.error("Error parsing a line from [{}]", META_PROPS.voiResFileName)
          log.error(error.getMessage)
        }
      }
    })

    /*voiFaultIterator.foreach( readResult => {
      readResult match {
        // Right side of read result is our actual value, IF everything went well reading it
        case Right(voiFault) => {
          faultIndexMatrix.putScalar(Array(voiFault.nx, voiFault.ny, voiFault.nz), voiFault.index)
        }
        case Left(error) => {
          log.error("Error parsing a line from [{}]", META_PROPS.voiFaultFileName)
          log.error(error.getMessage)
        }
      }
    })*/

    // At this point the reservoirMatrix should be populated with the data, return it
    reservoirMatrix
  }
  //------------------------------------------------------------------------//
  // End Private Processing Functions
  //------------------------------------------------------------------------//
}
