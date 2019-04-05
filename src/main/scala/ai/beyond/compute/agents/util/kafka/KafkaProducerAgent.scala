package ai.beyond.compute.agents.util.kafka

import akka.actor.{Actor, ActorLogging, Props}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}

object KafkaProducerAgent {
  def props(producer: KafkaProducer[String, String]) = {
    Props(new KafkaProducerAgent(producer))
  }

  def name: String = "computecluster-kafkaproducer-agent"

  case class Message(topic: String, key: String, message: String)
}

class KafkaProducerAgent(val producer: KafkaProducer[String, String]) extends Actor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import KafkaProducerAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name

  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("KafkaProducer Agent - {} - starting", id)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "KafkaProducer Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("KafkaProducer Agent - {} - stopped", id)
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//

  override def receive: Receive = {
    case Message(topic, key, message) =>
      log.debug("KafkaProducer Agent - {} - Received message to produce message over Kafka", id)
      produce(topic, key, message)
  }

  def produce(topic: String, msgKey: String, msg: String): Unit = {

    val data = new ProducerRecord[String,String](topic, msgKey, msg)

    // Send the message async
    producer.send(data, produceCallback)
  }

  private val produceCallback = new Callback {

    // TODO: Build out this onCompletion method and check for potential exception in body
    override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
      log.debug("published: " + metadata.toString)
    }

  }
}
