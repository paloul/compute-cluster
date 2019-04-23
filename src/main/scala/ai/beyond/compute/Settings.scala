package ai.beyond.compute

import java.util.Properties

import scala.concurrent.duration._
import akka.actor._
import com.typesafe.config.Config

// This companion object here should not be touched, its basic infrastructure support
// to help create a connection between our application.conf file, Settings class
// and the Actor System.
object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  // The apply method is a scala way of working with
  // companion object and instantiation of classes
  def apply(config: Config): Settings = new Settings(config)

  // The lookup method is required by ExtensionIdProvider,
  // so we return ourselves here, this allows us
  // to configure our extension to be loaded when
  // the ActorSystem starts up
  override def lookup = Settings

  // This method will be called by Akka to instantiate our Extension
  override def createExtension(system: ExtendedActorSystem): Settings = apply(system.settings.config)

  // Needed to get the type right when used from Java
  override def get(system: ActorSystem): Settings = super.get(system)
}

class Settings(config: Config) extends Extension {

  def this(system: ExtendedActorSystem) = this(system.settings.config)

  // Holds config params from application.conf concerning hdfs
  object hdfs {
    val hdfsBase: String = config.getString("application.hdfs.base-path")
  }

  object sia {
    object files {
      val basePath: String = config.getString("application.sia.files.base-path")
    }
  }

  // Holds config params from application.conf concerning the Cluster App settings
  object cluster {
    val name: String = config.getString("application.cluster.name")
  }

  // Holds config params from application.conf concerning the HTTP API settings
  object http {
    val host: String = config.getString("application.http.host")
    val port: Int = config.getInt("application.http.port")
    var requestTimeout: Duration = Duration(config.getString("application.http.request-timeout"))
  }

  object kafka {
    // All these are used by the underlying kafka library
    val bootstrapServers: String = config.getString("application.kafka.bootstrap.servers")
    val lingerMilliSeconds: String = config.getString("application.kafka.linger.ms")
    val acks: String = config.getString("application.kafka.acks")
    val bufferMemory: String = config.getString("application.kafka.buffer.memory")
    val maxBlockMilliSeconds: String = config.getString("application.kafka.max.block.ms")

    // This one is used by Kafka Master Agent, defines number of children workers to start
    val numberProducerAgents: Int = config.getInt("application.kafka.num-producer-agents")

    val props = new Properties()
    props.put("acks", acks)
    props.put("buffer.memory", bufferMemory)
    props.put("linger.ms", lingerMilliSeconds)
    props.put("bootstrap.servers", bootstrapServers)
    props.put("max.block.ms", maxBlockMilliSeconds)
    props.put("client.id", "ai.beyond.compute-cluster")
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  }

  object mongo {

    val uri: String = config.getString("application.mongo.uri")
    val database: String = config.getString("application.mongo.database")

    // This one is used by Mongo Master Agent, defines number of children workers to start
    val numberMongoDbAgents: Int = config.getInt("application.mongo.num-worker-agents")

    val computeAgentJobsCollection: String = config.getString("application.mongo.compute-agent-jobs.collection")
  }

  // ******************************************************************************
  // Any additional custom settings can be added here.
  // Anything added here should also reside in the application.conf file (if you intend to use it - obviously)

  // ******************************************************************************
}
