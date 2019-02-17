package ai.beyond.paloul.fintech.agents

import ai.beyond.paloul.fintech.sharded.ShardedMessages
import akka.actor.{Actor, ActorLogging, Props}

object WorkAgent extends ShardedMessages {
  def props(agentId: String) = Props(new WorkAgent)

  trait Message extends ShardedMessage

  // Messages specific to the WorkAgent
  case class PrintPath(agentId: String) extends Message
  case class HelloThere(agentId: String, msgBody: String) extends Message
}

class WorkAgent extends Actor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import WorkAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name


  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("Work Agent - {} - starting", id)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "Work Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("Work Agent - {} - stopped", id)
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//


  override def receive: Receive = {
    case PrintPath(agentId) =>
      log.info("I've been told to print my path: {}", agentId)

    case HelloThere(agentId, msgBody) =>
      log.info("({}) Hello there, you said, '{}'", agentId, msgBody)

  }

}
