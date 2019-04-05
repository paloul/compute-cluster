package ai.beyond.compute.sharded

import akka.cluster.sharding.ShardRegion

// Sharded Messages trait defines some commonly used definitions and routines
// for companion objects of all agent types across the cluster.
// For instance the extractEntityId and extractShardId need to be present
// to all companion objects and underlying message types in order to complete
// routing across nodes/shards/agents.
trait ShardedMessages {

  // The default basic Stop message, each Agent's companion object will define this
  // as a potential stop message for themselves. Cannot utilize this object directly, i.e.
  // as a ShardedMessages.Stop, once an Agent class's companion object extends ShardedMessages
  // they will have access to the Stop message, i.e. ComputeAgent.Stop.
  // To see how this Stop message is used, check out the override unhandled(msg: Any) function
  // in each Sharded Agent class, i.e. ShardedComputeAgent.
  case object Stop

  // Ths base SharedMessage Trait. This is a trait that all other base Agent Messages
  // should extend. By extending this trait, the agentId is carried over to all messages
  // which is a requirement for the cluster sharding to work. This id is used to direct
  // messages to where they need to go, node/shard/agent.
  // This trait and agentId is also required for the methods below, extractEntityId and
  // extractShardId. The extractEntityId and extractShardId are two application specific
  // functions to extract the entity identifier and the shard identifier from incoming messages.
  trait ShardedMessage {
    //def id: Long // ID as a Long, more restrictive ids
    def id: String // ID as a string, more flexible ids
  }

  // Extract the parts from the message, id and msg, separately, and return
  // it as a tuple. This will help identify the intended agent entity
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: ShardedMessage => (msg.id.toString, msg)
  }

  // A shard is a group of actor entities that will be managed together. The grouping is defined
  // by the extractShardId function. For a specific entity identifier the shard identifier must
  // always be the same. Otherwise the entity actor might accidentally be started in several
  // places at the same time.
  // Creating a good sharding algorithm is an interesting challenge in itself. Try to produce a
  // uniform distribution, i.e. same amount of entities in each shard. As a rule of thumb, the
  // number of shards should be a factor ten greater than the planned maximum number of cluster nodes.
  // Less shards than number of nodes will result in that some nodes will not host any shards.
  // Too many shards will result in less efficient management of the shards, e.g. rebalancing overhead,
  // and increased latency because the coordinator is involved in the routing of the first message
  // for each shard. The sharding algorithm must be the same on all nodes in a running cluster.
  val extractShardId: ShardRegion.ExtractShardId = {
    //case msg: ShardedMessage => (msg.agentId % 7).toString // Requires the Long type version of ID
    case msg: ShardedMessage => (msg.id.hashCode % 7).toString // Requires the String type version of ID

    // ShardRegion.StartEntity is used when remembering entities feature is turned on,
    // by default remembering entities is off, but this is here for future compatibility
    // in case someone forgets. When off, this does nothing as no message is generated.
    // When on, the ShardRegion.StartEntity message is generated for each agent that was
    // alive that needs to be automatically restarted due to node/shard shutdown or migration.
    //case ShardRegion.StartEntity(id) => (id.toLong % 7).toString // Requires the Long type version of ID
    case ShardRegion.StartEntity(id) => (id.hashCode % 7).toString // Requires the String type version of ID
  }

}