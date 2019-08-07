package com.paloul.compute.sharded.sample

import akka.actor.Props
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import com.paloul.compute.Settings
import com.paloul.compute.agents.sample.ComputeAgent
import com.paloul.compute.agents.sample.computeagentprotocol._

// Companion object for ShardedComputeAgent. Overall just
// a helper object that defines the underlying required
// extractId and extractShard functions for functioning in sharded clusters.
object ShardedComputeAgent {
  def props(settings: Settings) = Props(new ShardedComputeAgent(settings))

  val shardName: String = "sample-compute-agents"

  // Extract the parts from the message, id and msg, separately, and return
  // it as a tuple. This will help identify the intended agent entity and
  // forward the underlying envelope contents to the right agent
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case env: ShardedEnvelope =>
      env.contents match {

        // Any Message intended to be routed over sharded cluster should be added here
        case ShardedEnvelope.Contents.InitiateStop(initiateStop) => (env.actorId, initiateStop)
        case ShardedEnvelope.Contents.PrintPath(printPath) => (env.actorId, printPath)
        case ShardedEnvelope.Contents.RepeatMe(repeatMe) => (env.actorId, repeatMe)
        case ShardedEnvelope.Contents.DoWork(doWork) => (env.actorId, doWork)

      }
  }

  // A shard is a group of actor entities that will be managed together. The grouping is defined
  // by the extractShardId function. For a specific entity identifier the shard identifier must
  // always be the same. Otherwise the entity actor might accidentally be started in several
  // places at the same time.
  val extractShardId: ShardRegion.ExtractShardId = {
    case env: ShardedEnvelope => (env.actorId.hashCode % 7).toString

    // ShardRegion.StartEntity is used when remembering entities feature is turned on,
    // by default remembering entities is off, but this is here for future compatibility
    // in case someone forgets. When off, this does nothing as no message is generated.
    // When on, the ShardRegion.StartEntity message is generated for each agent that was
    // alive that needs to be automatically restarted due to node/shard shutdown or migration.
    case ShardRegion.StartEntity(actorId) => (actorId.hashCode % 7).toString
  }
}

class ShardedComputeAgent(settings: Settings) extends ComputeAgent(settings) {

  // Default handler for any UNHANDLED messages received not captured by
  // the base class. Any messages received by an Agent that
  // are unhandled by its current RECEIVE definition are caught here
  override def unhandled(msg: Any): Unit = msg match {

    // Initiate the stop process. This is initiated so that we can tell our parent to passivate us.
    // This helps maintain order and have the parent stop sending messages to the child without knowing
    // that the child has stopped. The passivate message helps avoid that.
    case InitiateStop() =>
      // When you receive this timeout message, tell your parent to passivate you.
      // You can capture other behavior here but on this line we are telling our parent
      // to Passivate us with a Stop message which we capture below. By telling the Parent
      // to initiate the Stop/Passivate the system halts all other messages from being sent out
      // to the child, therefore putting a stop to all outgoing messages intended for the child
      // before sending it the official stop message.
      log.info("Received command to initiate stop, sending Passivate for [{}]", agentId)
      context.parent ! Passivate(stopMessage = Stop)

    // A Stop message was received so we stop ourselves
    case Stop =>
      log.info("Received stop command from parent. Stopping myself [{}]", agentId)
      context.stop(self)

    // Catch the unhandled message, as Scala Match throws an error scala.MatchError if we don't catch them
    case _ =>
      log.warn("Received unknown message that was unhandled, ignoring")
      context.sender ! "Moron, I don't know how to process your message"
  }

}
