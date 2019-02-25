package ai.beyond.fpt.mvp.compute

import scala.concurrent.duration._
import akka.actor._
import akka.kafka.ProducerSettings
import com.typesafe.config.Config
import org.apache.kafka.common.serialization.StringSerializer

// This companion object here should not be touched, its basic infrastructure support
// to help create a connection between our application.conf file, Settings class
// and the Actor System.
object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  // Formatted to support instantiation only once and then refer back
  // to instantiation when needed again. The apply method is a scala
  // way of working with companion object and instantiation of classes
  var settings: Option[Settings] = None
  def apply(config: Config): Settings = {
    if (!settings.isDefined)
      settings = Some(new Settings(config))

    settings.get
  }

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
    val kafkaConfig = config.getConfig("akka.kafka.producer")
    val producerSettings = ProducerSettings(config, new StringSerializer, new StringSerializer)
  }

  // ******************************************************************************
  // Any additional custom settings can be added here.
  // Anything added here should also reside in the application.conf file (if you intend to use it - obviously)

  // ******************************************************************************
}
