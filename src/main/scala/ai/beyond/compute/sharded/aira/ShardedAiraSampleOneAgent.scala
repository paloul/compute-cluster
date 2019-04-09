package ai.beyond.compute.sharded.aira

import ai.beyond.compute.agents.aira.AiraSampleOneAgent
import ai.beyond.compute.sharded.ShardedMessages
import akka.actor.{Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate

// Companion object for ShardedAiraSampleOneAgent. Overall just
// a helper object to gain access to the underlying
// ShardedMessages extractId and extractShard functions.
// Along with giving algorithm agents a shard name.
object ShardedAiraSampleOneAgent extends ShardedMessages {
  def props = Props(new ShardedAiraSampleOneAgent)
  def name(agentId: Long): String = agentId.toString

  val shardName: String = "aira-sample-one-agents"
}

class ShardedAiraSampleOneAgent extends AiraSampleOneAgent {

  // Capture when an instance was created, val because it shouldn't change
  val objCreationTime = System.nanoTime()

  // Default handler for any UNHANDLED messages received not captured by
  // the base class. Any messages received by an Agent that
  // are unhandled by its current RECEIVE definition are caught here
  override def unhandled(msg: Any) = msg match {
    // Received a ReceiveTimeout message from the context. This message is initiated
    // by the call above to context.setReceiveTimeout after x seconds have passed
    case ReceiveTimeout =>
      // When you receive this timeout message, tell your parent to passivate you.
      // You can capture other behavior here but on this line we are telling our parent
      // to Passivate us with a Stop message which we capture below. By telling the Parent
      // to initiate the Stop/Passivate the system halts all other messages from being sent out
      // to the child, therefore putting a stop to all outgoing messages intended for the child
      // before sending it the official stop message.
      log.info("Received Timeout message, initiating Passivate for self [{}]", self.path.toString)
      context.parent ! Passivate(stopMessage = AiraSampleOneAgent.Stop)

    // A Stop message was received so we stop ourselves
    case AiraSampleOneAgent.Stop => context.stop(self)

    // Catch the unhandled message, as Scala Match throws an error scala.MatchError, if we don't catch them
    case _ => log.warning("Received unknown message that was unhandled, ignoring")
  }

}
