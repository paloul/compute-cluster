package ai.beyond.paloul.fintech.sharded

import ai.beyond.paloul.fintech.agents.StockPriceAgent
import akka.actor.{Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate

// Companion object for ShardedStockPriceAgent. Overall just
// a helper object to gain access to the underlying
// ShardedMessages extractId and extractShard functions.
// Along with giving algorithm agents a shard name.
object ShardedStockPriceAgent extends ShardedMessages {
  def props = Props(new ShardedStockPriceAgent)
  def name(agentId: Long): String = agentId.toString

  val shardName: String = "stock-price-agents"
}

class ShardedStockPriceAgent extends StockPriceAgent {

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
      // to Passivate us with a Stop message which we capture below
      log.info("Received Timeout message, initiating Passivate for self [{}]", self.path.toString)
      context.parent ! Passivate(stopMessage = StockPriceAgent.Stop)

    // A Stop message was received so we stop ourselves
    case StockPriceAgent.Stop => context.stop(self)
  }

}
