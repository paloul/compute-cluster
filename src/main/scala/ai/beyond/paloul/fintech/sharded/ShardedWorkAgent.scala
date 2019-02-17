package ai.beyond.paloul.fintech.sharded

import ai.beyond.paloul.fintech.agents.WorkAgent
import akka.actor.{Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate

object ShardedWorkAgent extends ShardedMessages {
  def props = Props(new ShardedWorkAgent)

  val shardName: String = "work-agents"
}

class ShardedWorkAgent extends WorkAgent {

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
      context.parent ! Passivate(stopMessage = WorkAgent.Stop)

    // A Stop message was received so we stop ourselves
    case WorkAgent.Stop => context.stop(self)
  }

}
