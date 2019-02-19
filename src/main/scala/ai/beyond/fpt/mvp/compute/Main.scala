package ai.beyond.fpt.mvp.compute

import ai.beyond.fpt.mvp.compute.rest.RestServiceSupport
import ai.beyond.fpt.mvp.compute.sharded.ShardedAgents
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object Main extends App with RestServiceSupport {

  // Load the application.conf file and create our own Settings helper class
  val config = ConfigFactory.load()
  val settings: Settings = new Settings(config)

  // Seed node names in application.conf should match the label used here for ActorSystem
  // i.e "akka.tcp://"${application.cluster.name}"@127.0.0.1:2551"
  // Look in application.conf for the list defined by {akka.cluster.seed-nodes}
  implicit val system: ActorSystem = ActorSystem(settings.cluster.name, config)

  // Get the main actor type to be used for sharded cluster of actors
  // ShardedAgents deals with identifying incoming requests and routing them
  // correctly to the right agent type.
  // Start the Rest Service hosted by RestServiceSupport
  startRestService(system.actorOf(ShardedAgents.props, ShardedAgents.name), settings)
}
