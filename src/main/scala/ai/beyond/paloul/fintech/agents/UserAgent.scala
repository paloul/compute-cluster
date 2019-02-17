package ai.beyond.paloul.fintech.agents

import ai.beyond.paloul.fintech.sharded.ShardedMessages
import akka.actor.{ActorLogging, Props}
import akka.persistence.PersistentActor


// ******************************************************************************
// Background Information on Akka Persistence
// LINK: https://doc.akka.io/docs/akka/2.5/persistence.html
//
// Akka persistence enables stateful actors to persist their state so that it can be recovered
// when an actor is either restarted, such as after a JVM crash, by a supervisor or a manual
// stop-start, or migrated within a cluster. The key concept behind Akka persistence is that
// only the events received by the actor are persisted, not the actual state of the actor
// (though actor state snapshot support is also available). The events are persisted by appending
// to storage (nothing is ever mutated) which allows for very high transaction rates and efficient
// replication. A stateful actor is recovered by replaying the stored events to the actor, allowing
// it to rebuild its state. This can be either the full history of changes or starting from a checkpoint
// in a snapshot which can dramatically reduce recovery times. Akka persistence also provides
// point-to-point communication with at-least-once message delivery semantics.
// ******************************************************************************


// The companion object for UserAgent class actor. Defines the messages used by
// the UserAgent class actor as well as other supporting "static" methods as needed
object UserAgent extends ShardedMessages {
  def props(agentId: String) = Props(new UserAgent)

  // The base Message trait as a catch all for Events and Commands for the User agent
  // Event and Command types should extend this, it just helps organize and route
  // messages, events, commands to UserAgents
  trait Message extends ShardedMessage

  // Persistent Agents use different terminology to make better sense of their lifetime and
  // persistent capabilities.
  // https://doc.akka.io/docs/akka/2.5/cluster-sharding.html
  trait Event extends Message // An event that is persisted
  trait Command extends Message // A command that initiates a persisted event

  // Messages specific to the UserAgent
  case class HelloThere(agentId: String, msgBody: String) extends Message

  // Commands specific to the UserAgent
  case class Increment(agentId: String, delta: Int) extends Command
  case class Decrement(agentId: String, delta: Int) extends Command

  // Events specific to the UserAgent
  case class CounterChanged(agentId: String, delta: Int) extends Event
}

// The UserAgent class actor responsible for representing a user in the system.
// This actor agent is Persistent, meaning if shutdown, either intentionally or
// accidentally due to errors and/or crashes, it will be restarted with its state intact.
class UserAgent extends PersistentActor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import UserAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  // Persistence Id needs to be set as this is required of from PersistentActor
  override def persistenceId = "UserAgent-" + self.path.name


  //------------------------------------------------------------------------//
  // Actor State Variables
  //------------------------------------------------------------------------//
  // Keeps persistent count for this user across start/stop/migration events
  private var count: Int = 0
  //------------------------------------------------------------------------//
  // End Actor State Variables
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Actor Lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("User Agent - {} - starting", persistenceId)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "User Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("User Agent - {} - stopped", persistenceId)
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // Start UserAgent Custom Functions
  //------------------------------------------------------------------------//
  def updateCounter(event: CounterChanged): Unit = {
    count += event.delta

    log.info("My Counter was changed by {}. It is now ({})", event.delta, count)
  }
  //------------------------------------------------------------------------//
  // End UserAgent Custom Functions
  //------------------------------------------------------------------------//


  //------------------------------------------------------------------------//
  // UserAgent Receive Functions
  //------------------------------------------------------------------------//
  // Receive Recover function is used to replay events back to the
  // Persisted Agent after a restart
  override def receiveRecover: Receive = {
    case event: CounterChanged ⇒ updateCounter(event)
  }

  // Receive Command function is the main live receive handler while
  // agent is alive and well
  override def receiveCommand: Receive = {
    case HelloThere(agentId, msgBody) =>
      log.info("({}) Hello there, you said, '{}'", agentId, msgBody)

    case Increment(agentId, delta) =>
      log.info("({}) Receiving Command to Increment my Counter by {}", agentId, delta)
      persist(CounterChanged(agentId, delta))(updateCounter)

    case Decrement(agentId, delta) =>
      log.info("({}) Receiving Command to Decrement my Counter by {}", agentId, delta)
      persist(CounterChanged(agentId, delta))(updateCounter)
  }
  //------------------------------------------------------------------------//
  // End UserAgent Receive Functions
  //------------------------------------------------------------------------//


}
