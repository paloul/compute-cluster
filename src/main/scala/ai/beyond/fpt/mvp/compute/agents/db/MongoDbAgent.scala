package ai.beyond.fpt.mvp.compute.agents.db

import ai.beyond.fpt.mvp.compute.Settings
import akka.actor.{Actor, ActorLogging, Props}
import org.mongodb.scala._

object MongoDbAgent {
  var mySettings: Option[Settings] = None

  def props(settings: Settings) = {

    mySettings = Some(settings)

    Props(new MongoDbAgent)
  }

  def name: String = "fpt-mongodb-agent"

  // Trait for all Mongo Messages, all messages below should extend this trait
  // This is so that we can more easily forward with a router all messages with base Mongo Message
  trait MongoMessage {
    val collection: String // The collection where to put the data
  }

  ///////////////////////
  // Messages our MongoDbAgent can receive
  ///////////////////////
  // Compute Job Meta Data message to store metadata of compute jobs to mongo
  // Remember in Scala, default parameters should be towards the end or else you need to define each param
  // in the call to the function/class specifically i.e. ComputeJobMetaData(id=idstr, name=namestr, etc)
  case class ComputeJobMetaData(id: String, name: String, owner: String, socketeer: String,
                collection: String = mySettings.get.mongo.computeAgentJobsCollection) extends MongoMessage
  ///////////////////////
}

class MongoDbAgent extends Actor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import MongoDbAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name

  // Reference to mongo client
  var mongoClient: Option[MongoClient] = None
  var mongoDatabase: Option[MongoDatabase] = None

  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("MongoDb Agent - {} - starting", id)

    // Create a mongo client and database using the settings ingested from the app.conf and stored in Settings class
    mongoClient = Some(MongoClient(mySettings.get.mongo.uri))
    if (mongoClient.isDefined) mongoDatabase = Some(mongoClient.get.getDatabase(mySettings.get.mongo.database))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "MongoDb Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("MongoDb Agent - {} - stopped", id)

    // Close connections to mongo client
    if (mongoClient.isDefined) mongoClient.get.close()
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//

  override def receive: Receive = {
    case ComputeJobMetaData(collection, id, name, owner, socketeer) =>
      log.debug("MongoDb Agent - {} - Received message to store in Mongo", id)
      saveComputeJobMetaData(collection, id, name, owner, socketeer)
  }

  def saveComputeJobMetaData(collection: String, id: String, name: String, owner: String, socketeer: String): Unit = {

    if (mongoDatabase.isEmpty) {
      log.warning("Mongo Database not correctly initialized. Missing Database connection")
    }

    if (mongoDatabase.isDefined) {
      // Get collection to store job meta data to
      val col: MongoCollection[Document] = mongoDatabase.get.getCollection(collection)

      // Create the BSON. ID needs to be _id in the actual Document so that Mongo will treat it as defacto ID of doc
      // Here the ID is actually the Job ID, it is unique and there should be only one entry of ID in the Mongo Coll
      val doc: Document = Document("_id" -> id, "name" -> name, "owner" -> owner, "socketeer" -> socketeer)

      // In the API all methods returning a Observables are “cold” streams meaning that
      // nothing happens until they are Subscribed to. This is more commonly known as lazy loading
      val observable: Observable[Completed] = col.insertOne(doc)
      // Explicitly subscribe to activate execution of insertOne
      observable.subscribe(new Observer[Completed] {

        override def onNext(result: Completed): Unit =
          log.info("Compute Job [{}] MetaData inserted: {}", id, doc.toString())

        override def onError(e: Throwable): Unit =
          log.error("Compute Job [{}] MetaData failed: {}", id, doc.toString())

        override def onComplete(): Unit =
          log.info("Compute Job [{}] MetaData completed: {}", id, doc.toString())
      })

    }

  }
}
