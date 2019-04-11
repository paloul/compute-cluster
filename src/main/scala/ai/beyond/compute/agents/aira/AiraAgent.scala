package ai.beyond.compute.agents.aira

import ai.beyond.compute.agents.util.db.MongoMasterAgent
import ai.beyond.compute.agents.util.kafka.KafkaMasterAgent
import ai.beyond.compute.logging.aira.AiraAgentLogging
import akka.actor.{Actor, ActorSelection}
import org.apache.spark.sql.SparkSession

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

  // Stores a reference to the Spark session
  var spark: SparkSession = _


  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    super.preStart()

    // Get reference to helper agents
    mongoMasterAgentRef = actorSelection("/user/" + MongoMasterAgent.name)
    kafkaMasterAgentRef = actorSelection("/user/" + KafkaMasterAgent.name)

    // Start the Spark session
    spark = SparkSession.builder()
        .master("local[*]")
        .appName(agentName)
        .getOrCreate()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    super.postStop()
    spark.close()
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//
}
