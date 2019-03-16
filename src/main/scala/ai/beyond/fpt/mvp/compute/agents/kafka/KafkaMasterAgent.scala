package ai.beyond.fpt.mvp.compute.agents.kafka

import ai.beyond.fpt.mvp.compute.Settings
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}

object KafkaMasterAgent {

  var mySettings: Option[Settings] = None

  def props(settings: Settings) = {

    mySettings = Some(settings)

    Props(new KafkaMasterAgent)
  }

  def name: String = "fpt-kafka-master-agent"

}

class KafkaMasterAgent extends Actor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import KafkaMasterAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name

  // Create the children Producer agents and add to Route definition
  // The routing logic is basic Round Robin, akka.routing.RoundRobinRoutingLogic
  // Create the routees as ordinary child actors wrapped in ActorRefRoutee.
  // Watch the routees to be able to replace them if they are terminated with context watch r
  // In the actual Receive func block we will look for Terminated message and recreate kid(s) as necessary
  var router = {
    // Automatically fill a vector with x number of Kafka Producers (defined in app.conf/settings helper)
    val routees = Vector.fill(mySettings.get.kafka.numberProducerAgents) {
      // Create a child Kafka Producer
      val routee = context.actorOf(Props[KafkaProducerAgent])
      // Watch the new child Kafka Producer
      context watch routee
      // Get routee actor ref to be put in vector
      ActorRefRoutee(routee)
    }

    // Create the router with Round Robin logic
    Router(RoundRobinRoutingLogic(), routees)
  }

  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("KafkaMaster Agent - {} - starting", id)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "KafkaMaster Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("KafkaMaster Agent - {} - stopped", id)
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//


  override def receive = {
    case kafkaMessage: KafkaProducerAgent.Message ⇒
      // Forward the message intended over kafka to underlying Kafka Producer Agents
      router.route(kafkaMessage, sender())

    // Received a child terminated message, val a refers to the child
    case Terminated(a) ⇒
      // Remove terminated child from current router routee list
      router = router.removeRoutee(a)
      // Create a new child Kafka Producer
      val r = context.actorOf(Props[KafkaProducerAgent])
      // Watch the new child Kafka Producer
      context watch r
      // Add the new child Kafka Producer to our router
      router = router.addRoutee(r)
  }

}
