package ai.beyond.paloul.fintech.sharded

import ai.beyond.paloul.fintech.agents.{AlgorithmAgent, StockPriceAgent, UserAgent, WorkAgent}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

object ShardedAgents {
  def props = Props(new ShardedAgents)
  def name: String = "fintech-sharded-agents"
}

class ShardedAgents extends Actor with ActorLogging {

  // Start the cluster shard system and manager for the StockPrice agents
  val shardedStockPriceAgents: ActorRef = ClusterSharding(context.system).start(
    ShardedStockPriceAgent.shardName,
    ShardedStockPriceAgent.props,
    ClusterShardingSettings(context.system),
    ShardedStockPriceAgent.extractEntityId,
    ShardedStockPriceAgent.extractShardId
  )

  // Start the cluster shard system and manager for the Algorithm agents
  val shardedAlgorithmAgents: ActorRef = ClusterSharding(context.system).start(
    ShardedAlgorithmAgent.shardName,
    ShardedAlgorithmAgent.props,
    ClusterShardingSettings(context.system),
    ShardedAlgorithmAgent.extractEntityId,
    ShardedAlgorithmAgent.extractShardId
  )

  val shardedWorkAgents: ActorRef = ClusterSharding(context.system).start(
    ShardedWorkAgent.shardName,
    ShardedWorkAgent.props,
    ClusterShardingSettings(context.system),
    ShardedWorkAgent.extractEntityId,
    ShardedWorkAgent.extractShardId
  )

  val shardedUserAgents: ActorRef = ClusterSharding(context.system).start(
    ShardedUserAgent.shardName,
    ShardedUserAgent.props,
    ClusterShardingSettings(context.system),
    ShardedUserAgent.extractEntityId,
    ShardedUserAgent.extractShardId
  )

  override def receive: Receive = {
    // In order to route to the correct type of sharded cluster, the case statements
    // are the more general Message type that all other messages should inherit
    // from within their companion object. This way this receive function stays fairly
    // concise, matching the number of sharded agent types in the cluster

    case cmd: StockPriceAgent.Message =>
      shardedStockPriceAgents forward cmd

    case cmd: AlgorithmAgent.Message =>
      shardedAlgorithmAgents forward cmd

    case cmd: WorkAgent.Message =>
      shardedWorkAgents forward cmd

    case cmd: UserAgent.Message =>
      shardedUserAgents forward cmd
  }

}
