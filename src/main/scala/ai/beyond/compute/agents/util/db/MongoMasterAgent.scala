package ai.beyond.compute.agents.util.db

import ai.beyond.compute.Settings
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import org.mongodb.scala.{MongoClient, MongoDatabase}

object MongoMasterAgent {

  var mySettings: Option[Settings] = None

  // Reference to mongo client
  var mongoClient: Option[MongoClient] = None
  var mongoDatabase: Option[MongoDatabase] = None

  def props(settings: Settings) = {

    mySettings = Some(settings)

    // Create a mongo client and database conn using the settings from the app.conf and stored in Settings class
    // Reference to this producer is passed down to the children to use, we close the mongo client
    // in the master when the master is shutdown, the children don't have to deal with closing it
    mongoClient = Some(MongoClient(mySettings.get.mongo.uri))
    if (mongoClient.isDefined) mongoDatabase = Some(mongoClient.get.getDatabase(mySettings.get.mongo.database))

    Props(new MongoMasterAgent)
  }

  def name: String = "computecluster-mongo-master-agent"

  // Trait for all Mongo Messages, all messages below should extend this trait
  // This is so that we can more easily forward with a router all messages with base Mongo Message
  trait MongoMessage {
    val collection: String // The collection where to put the data
  }

}

class MongoMasterAgent extends Actor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import MongoMasterAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name

  // Create the children Producer agents and add to Route definition
  // The routing logic is basic Round Robin, akka.routing.RoundRobinRoutingLogic
  // Create the routees as ordinary child actors wrapped in ActorRefRoutee.
  // Watch the routees to be able to replace them if they are terminated with context watch r
  // In the actual Receive func block we will look for Terminated message and recreate kid(s) as necessary
  // Since, the master starts the children and is watching them, when the master is shutdown, so do the children
  var router = {
    // Automatically fill a vector with x number of Mongo Workers (defined in app.conf/settings helper)
    val routees = Vector.fill(mySettings.get.mongo.numberMongoDbAgents) {
      // Create a child Mongo Worker
      val routee = context.actorOf(MongoDbAgent.props(mySettings.get, mongoDatabase.get))
      // Watch the new child Mongo Worker
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
    log.info("MongoMaster Agent - {} - starting", id)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "MongoMaster Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("MongoMaster Agent - {} - stopped", id)

    // Close connections to mongo client
    if (mongoClient.isDefined) {
      mongoClient.get.close()
      mongoClient = None
    }
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//


  override def receive: Receive = {

    case msg: MongoMessage ⇒
      // Forward the message to underlying Mongo Worker Agents
      router.route(msg, sender())

    // Received a child terminated message, val a refers to the child
    case Terminated(a) ⇒
      // Remove terminated child from current router routee list
      router = router.removeRoutee(a)
      // Create a new child Mongo Worker
      val r = context.actorOf(MongoDbAgent.props(mySettings.get, mongoDatabase.get))
      // Watch the new child Mongo Worker
      context watch r
      // Add the new child Mongo Worker to our router
      router = router.addRoutee(r)
  }

}
