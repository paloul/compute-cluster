package ai.beyond.compute.sharded

import ai.beyond.compute.Settings
import ai.beyond.compute.agents.sample.ComputeAgent
import ai.beyond.compute.sharded.sample.ShardedComputeAgent
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

object ShardedAgents {
  var mySettings: Option[Settings] = None

  def props(settings: Settings): Props = {

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

  override def receive: Receive = {
    // In order to route to the correct type of sharded cluster, the case statements
    // are the more general Message type that all other messages should inherit
    // from within their companion object. This way this receive function stays fairly
    // concise, matching the number of sharded agent types in the cluster

    case computeMessage: ComputeAgent.Message =>
      shardedComputeAgents forward computeMessage
  }

}
