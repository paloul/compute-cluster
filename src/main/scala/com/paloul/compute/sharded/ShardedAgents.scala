package com.paloul.compute.sharded

import com.paloul.compute.Settings
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.paloul.compute.sharded.sample.ShardedComputeAgent
import com.paloul.compute.agents.sample.computeagentprotocol

object ShardedAgents {
  var settings: Option[Settings] = None

  def props(s: Settings): Props = {

    settings = Some(s)

    Props(new ShardedAgents)
  }

  def name: String = "compute-cluster-sharded-agents"
}

class ShardedAgents extends Actor with ActorLogging {

  // Import items from companion object
  import ShardedAgents._

  // Start the cluster shard system and manager for the Compute agents
  val shardedComputeAgents: ActorRef = ClusterSharding(context.system).start(
    ShardedComputeAgent.shardName,
    ShardedComputeAgent.props(settings.get),
    ClusterShardingSettings(context.system),
    ShardedComputeAgent.extractEntityId,
    ShardedComputeAgent.extractShardId
  )

  override def receive: Receive = {
    // In order to route to the correct type of sharded cluster, the case statements
    // are the more general Message type that all other messages should inherit
    // from within their companion object. This way this receive function stays fairly
    // concise, matching the number of sharded agent types in the cluster

    case computeAgentEnvelope: computeagentprotocol.ShardedEnvelope =>
      shardedComputeAgents forward computeAgentEnvelope
  }

}
