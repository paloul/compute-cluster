package ai.beyond.compute.agents.aira.geo

import java.time.Instant

import ai.beyond.compute.agents.aira.AiraAgent
import ai.beyond.compute.sharded.{ShardedAgents, ShardedMessages}
import akka.actor.{Cancellable, Props}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.pattern.ask
import akka.util.Timeout
import spray.json.DefaultJsonProtocol

import scala.concurrent.Await
import scala.concurrent.duration._
import org.apache.spark.sql.Encoders

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
                   trajPath: String, geoMeanPath: String, atlMatPath: String, atlGridPath: String,
                   dfResPath: String) extends Message
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
  // CSV Schema Specifications
  ///////////////////////

  //,Well identifier,Comp Name,Oil (BOPD),Gas (MCFD),Water (BWPD)
  case class ProdSchema(date: String, wellId: String, compName: String, oil: Float, gas: Float, water: Float)
  private val PROD_SCHEMA = Encoders.product[ProdSchema].schema

  //,X,Y,Z
  case class OwcSchema(ind: Int, x: Float, y: Float, z: Float)
  private val OWC_SCHEMA = Encoders.product[OwcSchema].schema

  //,2015,2016,2017,Confidence,CoefY,CoefX,Intercept,Xmin,Xmax,Ymin,Ymax,Sealing
  case class FaultSchema(ind: Int, y15: Int, y16: Int, y17: Int, confidence: Float, coefY: Float, coefX: Float,
                         intercept: Float, xMin: Float, xMax: Float, yMin: Float, yMax: Float, sealing: Int)
  private val FAULT_SCHEMA = Encoders.product[FaultSchema].schema

  //,WellName,StartPerf,EndPerf
  case class PerfSchema(ind: Int, wellName: String, startPerf: Int, endPerf: Int)
  private val PERF_SCHEMA = Encoders.product[PerfSchema].schema

  //Index,Well identifier,MD,X,Y,Z,TVD,DX,DY,AZIM,INCL,DLS
  case class TrajSchema(ind: Int, wellId: String, md: Float, x: Float, y: Float, z: Float, tvd: Float,
                        dx: Float, dy: Float, azim: Float, incl: Float, dls: Float)
  private val TRAJ_SCHEMA = Encoders.product[TrajSchema].schema

  //X,Y,Z,GR_mean,GR_std,NPSS_mean,NPSS_std,RHOB_mean,RHOB_std,Predicted Cluster,SWT_mean,SWT_std,PHIT_mean,PHIT_std,KOILT_mean,KOILT_std
  case class GeoMeanSchema(x: Float, y: Float, z: Float, grMean: Float, grStd: Float, npssMean: Float,
                           npssStd: Float, rhobMean: Float, rhobStd: Float, cluster: Float, swtMean: Float,
                           swtStd: Float, phitMean: Float, phitStd: Float, koiltMean: Float, koildStd: Float)
  private val GEO_MEAN_SCHEMA = Encoders.product[GeoMeanSchema].schema

  //type,nx,ny,nz
  case class AtlMatSchema(rtype: Int, nx: Int, ny: Int, nz: Int)
  private val ATL_MAT_SCHEMA = Encoders.product[AtlMatSchema].schema

  //nx,ny,nz,X,Y,Z
  case class AtlGridSchema(nx: Int, ny: Int, nz: Int, x: Float, y: Float, z: Float)
  private val ATL_GRID_SCHEMA = Encoders.product[AtlGridSchema].schema

  // TODO: The schema for this is the exact same as ATL_GRID! The difference between the two
  //  is that ATL_GRID has X/Y/Z values for all cells in the VOI, while df_res has the X/Y/Z
  //  values for only cells within the reservoir. Could they be merged, or one ignored?
  //,nx,ny,nz,X,Y,Z
  case class DfResSchema(ind: Int, nx: Int, ny: Int, nz: Int, x: Float, y: Float, z: Float)
  private val DF_RES_SCHEMA = Encoders.product[AtlGridSchema].schema

  ///////////////////////
}

// Collect json format instances into a support trait
// Helps marshall the messages between JSON received via HTTP APIs
trait GeoDynamicAgentJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  // Add any messages that you need to be marshalled back and forth from/to json
  implicit val itemFormat = jsonFormat10(GeoDynamicAgent.Start)
  implicit val stateFormat = jsonFormat4(GeoDynamicAgent.State)
}

class GeoDynamicAgent extends AiraAgent with GeoDynamicAgentJsonSupport {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._
  // Import the companion object above to use the messages defined for us
  import GeoDynamicAgent._
  // Import implicits specific to spark session
  import spark.implicits._

  // Constants
  val HDFS_BASE: String = ShardedAgents.mySettings.get.spark.hdfsBase

  // Meta property object to store any meta data
  object META_PROPS {
    var percentComplete: Int = 0 // 0-100
    var lastKnownUpdate: Long = 0 // UNIX Timestamp
  }

  // Temporary Cancellable future connected to the underlying compute job mimic block
  // See below - in the runCompute function
  var computeJob: Cancellable = _


  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    super.preStart()
    log.info("GeoDynamic Agent - {} - starting", agentPath)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)

    // Debugging information if agent is restarted
    log.error(reason, "GeoDynamic Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
  }

  override def postStop(): Unit = {
    super.postStop()
    log.info("GeoDynamic Agent - {} - stopped", agentPath)
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

    case Start(id, prodPath, owcPath, faultPath, perfPath, trajPath, geoMeanPath, atlMatPath, atlGridPath, dfResPath) =>
      log.info("Starting compute with job ID[{}]", id)
      sender ! State(id, "Starting", META_PROPS.percentComplete, META_PROPS.lastKnownUpdate)
      processSpark(id, prodPath, owcPath, faultPath, perfPath, trajPath, geoMeanPath, atlMatPath,
        atlGridPath, dfResPath)
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
  def processSpark(id: String, prodPath: String, owcPath: String, faultPath: String, perfPath: String,
                   trajPath: String, geoMeanPath: String, atlMathPath: String, atlGridPath: String,
                   dfResPath: String): Unit = {
    log.info("File paths (1/3): {}, {}, {}", prodPath, owcPath, faultPath)
    log.info("File paths (2/3): {}, {}, {}", perfPath, trajPath, geoMeanPath)
    log.info("File paths (3/3): {}, {}, {}", atlMathPath, atlGridPath, dfResPath)

    // Load the production dataset
    var prodDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(PROD_SCHEMA)
      .load(HDFS_BASE + prodPath)
      .as[ProdSchema]

    prodDs.printSchema()
    prodDs.show(2)

    // Load the OWCs dataset
    var owcDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(OWC_SCHEMA)
      .load(HDFS_BASE + owcPath)
      .as[OwcSchema]

    owcDs.printSchema()
    owcDs.show(2)

    // Load the faults dataset
    var faultDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(FAULT_SCHEMA)
      .load(HDFS_BASE + faultPath)
      .as[FaultSchema]

    faultDs.printSchema()
    faultDs.show(2)

    // Load the perforations dataset
    var perfDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(PERF_SCHEMA)
      .load(HDFS_BASE + perfPath)
      .as[PerfSchema]

    perfDs.printSchema()
    perfDs.show(2)

    // Load the trajectory dataset
    var trajDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(TRAJ_SCHEMA)
      .load(HDFS_BASE + trajPath)
      .as[TrajSchema]

    trajDs.printSchema()
    trajDs.show(2)

    // Load the geo mean dataset
    var geoMeanDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(GEO_MEAN_SCHEMA)
      .load(HDFS_BASE + geoMeanPath)
      .as[GeoMeanSchema]

    geoMeanDs.printSchema()
    geoMeanDs.show(2)

    // Load the ATL_MAT dataset
    var atlMatDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(ATL_MAT_SCHEMA)
      .load(HDFS_BASE + atlMathPath)
      .as[AtlMatSchema]

    atlMatDs.printSchema()
    atlMatDs.show(2)

    // Load the ATL_GRID dataset
    var atlGridDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(ATL_GRID_SCHEMA)
      .load(HDFS_BASE + atlGridPath)
      .as[AtlGridSchema]

    atlGridDs.printSchema()
    atlGridDs.show(2)

    // Load the df_res dataset
    var dfResDs = spark.read
      .format("csv")
      .option("header", "true")
      .schema(DF_RES_SCHEMA)
      .load(HDFS_BASE + dfResPath)
      .as[DfResSchema]

    dfResDs.printSchema()
    dfResDs.show(2)
  }

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
