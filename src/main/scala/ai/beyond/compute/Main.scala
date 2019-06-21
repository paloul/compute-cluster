package ai.beyond.compute

import ai.beyond.compute.rest.RestServiceSupport
import ai.beyond.compute.sharded.ShardedAgents
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object Main extends App with RestServiceSupport {

  // Load the application.conf file and create our own Settings helper class
  val config = ConfigFactory.load()
  val settings: Settings = Settings(config)

  // Seed node names in application.conf should match the label used here for ActorSystem
  // i.e "akka.tcp://"${application.cluster.name}"@127.0.0.1:2551"
  // Look in application.conf for the list defined by {akka.cluster.seed-nodes}
  implicit val system: ActorSystem = ActorSystem(settings.cluster.name, config)

  // Note: Here you can start any number of Actor types, especially utility based Actors
  //  that might need to be available on each main cluster node and not act part of cluster
  //system.actorOf(UtilAgent.props(settings), UtilAgent.name)

  // Get the main actor type to be used for sharded cluster of actors
  // ShardedAgents deals with identifying incoming requests and routing them
  // correctly to the right agent type.
  // Start the Rest Service hosted by RestServiceSupport
  startRestService(system.actorOf(ShardedAgents.props(settings), ShardedAgents.name), settings)
}
