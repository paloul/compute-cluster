package ai.beyond.compute.sharded

import ai.beyond.compute.Settings
import ai.beyond.compute.agents.aira.geo.GeoDynamicAgent
import ai.beyond.compute.agents.aira.sia.SiaAgent
import ai.beyond.compute.agents.sample.ComputeAgent
import ai.beyond.compute.sharded.aira.geo.ShardedGeoDynamicAgent
import ai.beyond.compute.sharded.aira.sia.ShardedSiaAgent
import ai.beyond.compute.sharded.sample.ShardedComputeAgent
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

object ShardedAgents {
  var mySettings: Option[Settings] = None

  def props(settings: Settings) = {

    mySettings = Some(settings)

    Props(new ShardedAgents)
  }

  def name: String = "compute-cluster-sharded-agents"
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

//  // Start the cluster shard system and manager for the GeoDynamic agents
//  val shardedGeoDynamicAgents: ActorRef = ClusterSharding(context.system).start(
//    ShardedGeoDynamicAgent.shardName,
//    ShardedGeoDynamicAgent.props,
//    ClusterShardingSettings(context.system),
//    ShardedGeoDynamicAgent.extractEntityId,
//    ShardedGeoDynamicAgent.extractShardId
//  )

  // Start the cluster shard system and manager for the Sia agents
  val shardedSiaAgents: ActorRef = ClusterSharding(context.system).start(
    ShardedSiaAgent.shardName,
    ShardedSiaAgent.props,
    ClusterShardingSettings(context.system),
    ShardedSiaAgent.extractEntityId,
    ShardedSiaAgent.extractShardId
  )

  override def receive: Receive = {
    // In order to route to the correct type of sharded cluster, the case statements
    // are the more general Message type that all other messages should inherit
    // from within their companion object. This way this receive function stays fairly
    // concise, matching the number of sharded agent types in the cluster

    case computeMessage: ComputeAgent.Message =>
      shardedComputeAgents forward computeMessage

    case siaMessage: SiaAgent.Message =>
      shardedSiaAgents forward siaMessage

//    case geoDynamicMessage: GeoDynamicAgent.Message =>
//      shardedGeoDynamicAgents forward geoDynamicMessage
  }

}
