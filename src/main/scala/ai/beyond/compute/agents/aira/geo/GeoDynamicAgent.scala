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
  case class DfResSchema(ind: Int, nx: Float, ny: Float, nz: Float, x: Float, y: Float, z: Float)
  private val DF_RES_SCHEMA = Encoders.product[DfResSchema].schema

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


  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    super.preStart()

    log.info("GeoDynamic Agent - {} - starting", agentPath)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "GeoDynamic Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))

    super.preRestart(reason, message)
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


  //------------------------------------------------------------------------//
  // Begin Compute Functions
  //------------------------------------------------------------------------//
  /**
    * Starts processing data with spark, initial read of data files provided as params
    *
    * 2019-04-13: Utilizing HDFS to store data and prepend provided data files with HDFS base
    * TODO: Potentiall to offer more flexibility take in full paths to files instead of forcing HDFS
    * @param id
    * @param prodPath
    * @param owcPath
    * @param faultPath
    * @param perfPath
    * @param trajPath
    * @param geoMeanPath
    * @param atlMathPath
    * @param atlGridPath
    * @param dfResPath
    */
  def processSpark(id: String, prodPath: String, owcPath: String, faultPath: String, perfPath: String,
                   trajPath: String, geoMeanPath: String, atlMathPath: String, atlGridPath: String,
                   dfResPath: String): Unit = {
    log.info("File paths (1/3): {}, {}, {}", prodPath, owcPath, faultPath)
    log.info("File paths (2/3): {}, {}, {}", perfPath, trajPath, geoMeanPath)
    log.info("File paths (3/3): {}, {}, {}", atlMathPath, atlGridPath, dfResPath)

    // Load the production dataset
    val prodDs = time (
      "Reading Production Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(PROD_SCHEMA)
          .load(HDFS_BASE + prodPath)
          .as[ProdSchema]
      }
    )

    prodDs.printSchema()
    prodDs.show(2)
    log.info("Production Data Set Length: [{}]", prodDs.count())

    // Load the OWCs dataset
    val owcDs = time (
      "Reading OWCs Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(OWC_SCHEMA)
          .load(HDFS_BASE + owcPath)
          .as[OwcSchema]
      }
    )

    owcDs.printSchema()
    owcDs.show(2)
    log.info("OWCs Data Set Length: [{}]", owcDs.count())

    // Load the faults dataset
    val faultDs = time (
      "Reading the Faults Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(FAULT_SCHEMA)
          .load(HDFS_BASE + faultPath)
          .as[FaultSchema]
      }
    )

    faultDs.printSchema()
    faultDs.show(2)
    log.info("Faults Data Set Length: [{}]", faultDs.count())

    // Load the perforations dataset
    val perfDs = time (
      "Reading the Perforations Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(PERF_SCHEMA)
          .load(HDFS_BASE + perfPath)
          .as[PerfSchema]
      }
    )

    perfDs.printSchema()
    perfDs.show(2)
    log.info("Perforations Data Set Length: [{}]", perfDs.count())

    // Load the trajectory dataset
    val trajDs = time (
      "Reading the Trajectory Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(TRAJ_SCHEMA)
          .load(HDFS_BASE + trajPath)
          .as[TrajSchema]
      }
    )

    trajDs.printSchema()
    trajDs.show(2)
    log.info("Trajectory Data Set Length: [{}]", trajDs.count())

    // Load the geo mean dataset
    val geoMeanDs = time (
      "Reading the Geo Mean Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(GEO_MEAN_SCHEMA)
          .load(HDFS_BASE + geoMeanPath)
          .as[GeoMeanSchema]
      }
    )

    geoMeanDs.printSchema()
    geoMeanDs.show(2)
    log.info("Geo Mean Data Set Length: [{}]", geoMeanDs.count())

    // Load the ATL_MAT dataset
    val atlMatDs = time (
      "Reading the ATL_MAT Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(ATL_MAT_SCHEMA)
          .load(HDFS_BASE + atlMathPath)
          .as[AtlMatSchema]
      }
    )

    atlMatDs.printSchema()
    atlMatDs.show(2)
    log.info("ATL_MAT Data Set Length: [{}]", atlMatDs.count())

    // Load the ATL_GRID dataset
    val atlGridDs = time (
      "Reading the ATL_GRID Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(ATL_GRID_SCHEMA)
          .load(HDFS_BASE + atlGridPath)
          .as[AtlGridSchema]
      }
    )

    atlGridDs.printSchema()
    atlGridDs.show(2)
    log.info("ATL_GRID Data Set Length: [{}]", atlGridDs.count())

    // Load the df_res dataset
    val dfResDs = time (
      "Reading the df_res Data Set", {
        spark.read
          .format("csv")
          .option("header", "true")
          .schema(DF_RES_SCHEMA)
          .load(HDFS_BASE + dfResPath)
          .as[DfResSchema]
      }
    )

    dfResDs.printSchema()
    dfResDs.show(2)
    log.info("df_res Data Set Length: [{}]", dfResDs.count())
  }

}
