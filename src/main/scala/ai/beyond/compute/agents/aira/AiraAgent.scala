package ai.beyond.compute.agents.aira

import ai.beyond.compute.agents.util.db.MongoMasterAgent
import ai.beyond.compute.agents.util.kafka.KafkaMasterAgent
import ai.beyond.compute.logging.aira.AiraAgentLogging
import ai.beyond.compute.sharded.ShardedAgents
import akka.actor.{Actor, ActorSelection}
//import org.apache.spark.sql.SparkSession

// TODO: Add anything else common between all Aira agents

abstract class AiraAgent extends Actor with AiraAgentLogging {
  // Import all available functions under the context handle, i.e. become, actorSelection, system
  import context._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def agentPath: String = self.path.toStringWithoutAddress
  def agentName: String = self.path.name

  // Get a reference to the helper agents. This should be updated in the
  // the agent lifecycle methods. TODO: Before using the ref maybe check if valid
  var mongoMasterAgentRef: ActorSelection = actorSelection("/user/" + MongoMasterAgent.name)
  var kafkaMasterAgentRef: ActorSelection = actorSelection("/user/" + KafkaMasterAgent.name)

  // Gets a reference to the Spark Session. Spark Sessions are unique to each JVM.
  // Agents will be sharing a Spark Session for each node. But since agents are run on
  // different threads, they are able to submit jobs in parallel using the same Spark Session
  // Note: Do NOT stop a Spark Session within Agent, as it will stop it JVM wide.
  // Note: This needs to be a val so implicits can be imported
  /*val spark: SparkSession = SparkSession.builder()
      .master(ShardedAgents.mySettings.get.spark.master)
      .appName(ShardedAgents.mySettings.get.spark.appName + " [" + agentName + "]")
      .getOrCreate()*/

  // Constants
  val HDFS_BASE: String = ShardedAgents.mySettings.get.hdfs.hdfsBase

  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    // Get reference to helper agents
    mongoMasterAgentRef = actorSelection("/user/" + MongoMasterAgent.name)
    kafkaMasterAgentRef = actorSelection("/user/" + KafkaMasterAgent.name)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {

  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//
}
