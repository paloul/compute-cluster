package ai.beyond.fpt.mvp.compute.agents

import ai.beyond.fpt.mvp.compute.Settings
import akka.actor.{Actor, ActorLogging, Props}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}

object MongoDbAgent {
  var mySettings: Option[Settings] = None

  def props(settings: Settings) = {

    mySettings = Some(settings)

    Props(new MongoDbAgent)
  }

  def name: String = "fpt-mongodb-agent"

  case class Message(topic: String, key: String, message: String)
}

class MongoDbAgent extends Actor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import MongoDbAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name

  //TODO: Mod this var kafkaProducer: Option[KafkaProducer[String, String]] = None

  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("KafkaProducer Agent - {} - starting", id)

    // Create a kafka producer using the settings ingested from the app.conf and stored in Settings class
    //TODO: Mod this kafkaProducer = Some(new KafkaProducer[String, String](mySettings.get.kafka.props))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "KafkaProducer Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("KafkaProducer Agent - {} - stopped", id)

    //TODO: Mod this if (kafkaProducer.isDefined) kafkaProducer.get.close()
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
    //TODO: Mod this if (kafkaProducer.isDefined) kafkaProducer.get.send(data, produceCallback)
  }

  private val produceCallback = new Callback {

    // TODO: Build out this onCompletion method and check for potential exception in body
    override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
      log.debug("published: " + metadata.toString)
    }

  }
}
