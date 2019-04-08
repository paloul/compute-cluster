package ai.beyond.compute.sharded

import ai.beyond.compute.agents.ComputeAgent
import ai.beyond.compute.agents.aira.AiraSampleOneAgent
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

object ShardedAgents {
  def props = Props(new ShardedAgents)
  def name: String = "fpt-sharded-agents"
}

class ShardedAgents extends Actor with ActorLogging {

  // Start the cluster shard system and manager for the Compute agents
  val shardedComputeAgents: ActorRef = ClusterSharding(context.system).start(
    ShardedComputeAgent.shardName,
    ShardedComputeAgent.props,
    ClusterShardingSettings(context.system),
    ShardedComputeAgent.extractEntityId,
    ShardedComputeAgent.extractShardId
  )

  // Start the cluster shard system and manager for the Compute agents
  val shardedAiraSampleOneAgents: ActorRef = ClusterSharding(context.system).start(
    ShardedAiraSampleOneAgent.shardName,
    ShardedAiraSampleOneAgent.props,
    ClusterShardingSettings(context.system),
    ShardedAiraSampleOneAgent.extractEntityId,
    ShardedAiraSampleOneAgent.extractShardId
  )

  override def receive: Receive = {
    // In order to route to the correct type of sharded cluster, the case statements
    // are the more general Message type that all other messages should inherit
    // from within their companion object. This way this receive function stays fairly
    // concise, matching the number of sharded agent types in the cluster

    case computeMessage: ComputeAgent.Message =>
      shardedComputeAgents forward computeMessage

    case airaSampleOneMessage: AiraSampleOneAgent.Message =>
      shardedAiraSampleOneAgents forward airaSampleOneMessage
  }

}
