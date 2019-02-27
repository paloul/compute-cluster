package ai.beyond.fpt.mvp.compute.agents

import ai.beyond.fpt.mvp.compute.Settings
import akka.actor.{Actor, ActorLogging, Props}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}

object KafkaProducerAgent {
  var mySettings: Option[Settings] = None

  def props(settings: Settings) = {

    mySettings = Some(settings)

    Props(new KafkaProducerAgent)
  }

  def name: String = "fpt-kafkaproducer-agent"

  case class Message(topic: String, partition: Int, key: String, message: String)
}

class KafkaProducerAgent extends Actor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import KafkaProducerAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name

  var kafkaProducer: Option[KafkaProducer[String, String]] = None

  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("KafkaProducer Agent - {} - starting", id)

    // Create a kafka producer using the settings ingested from the app.conf and stored in Settings class
    kafkaProducer = Some(new KafkaProducer[String, String](mySettings.get.kafka.props))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "KafkaProducer Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("KafkaProducer Agent - {} - stopped", id)

    if (kafkaProducer.isDefined) kafkaProducer.get.close()
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//

  override def receive: Receive = {
    case Message(topic, partition, key, message) =>
      log.info("KafkaProducer Agent - {} - Received message to produce message over Kafka", id)
      produce(topic, partition, key, message)
  }

  def produce(topic: String, partition: Int, msgKey: String, msg: String): Unit = {

    val data = new ProducerRecord[String,String](topic, partition, msgKey, msg)

    if (kafkaProducer.isDefined)
      kafkaProducer.get.send(data, produceCallback)
  }

  private val produceCallback = new Callback {

    override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
      log.info("published: " + metadata.toString)
    }

  }
}
