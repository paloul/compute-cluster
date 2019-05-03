package ai.beyond.compute

import ai.beyond.compute.agents.util.db.MongoMasterAgent
import ai.beyond.compute.agents.util.kafka.KafkaMasterAgent
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

  // Start the KafkaProducer Agent for this actor system. Each Actor System
  // will have one KafkaProducer that handles all interaction over Kafka.
  // All agents contained within an actor system will send akka messages
  // to the KafkaProducer Agent which in turn will broadcast over Kafka.
  // KafkaProducer Agent is local to the Actor System. Each Actor System
  // will have one that serves actors in it.
  // KafkaMasterAgent handles load distribution and Resiliency of Kafka Producer
  // It supervises, manages and distributes work to a pool of KafkaProducerAgents.
  // This allows for the capability to alter the Supervision and restart any Producer
  //  agent that crash due to underlying kafka library or whatever reason
  // The Kafka Master Agent is only accessible internally per cluster, no
  // external API access
  if (settings.kafka.numberProducerAgents > 0 )
    system.actorOf(KafkaMasterAgent.props(settings), KafkaMasterAgent.name)

  // Start the MongoDb Agent for this actor system. Each Actor System
  // will have one MongoDb agent that handles all interaction for mongo.
  // All agents contained within an actor system will interact with mongo
  // through this actor/agent. Each MongoDb Agent is local to the Actor System.
  // The underlying Mongo Scala Driver works with a pool of connections to
  // the mongo db cluster. No need to create multiple MongoDb Agents.
  // The Mongo Master Agent is only accessible internally per cluster, no
  // external API access
  if (settings.mongo.numberMongoDbAgents > 0)
    system.actorOf(MongoMasterAgent.props(settings), MongoMasterAgent.name)

  // Get the main actor type to be used for sharded cluster of actors
  // ShardedAgents deals with identifying incoming requests and routing them
  // correctly to the right agent type.
  // Start the Rest Service hosted by RestServiceSupport
  startRestService(system.actorOf(ShardedAgents.props(settings), ShardedAgents.name), settings)
}
