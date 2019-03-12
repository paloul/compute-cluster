package ai.beyond.fpt.mvp.compute.agents

import org.mongodb.scala._

import ai.beyond.fpt.mvp.compute.Settings
import akka.actor.{Actor, ActorLogging, Props}
import org.apache.kafka.clients.producer.{Callback, ProducerRecord, RecordMetadata}

object MongoDbAgent {
  var mySettings: Option[Settings] = None

  def props(settings: Settings) = {

    mySettings = Some(settings)

    Props(new MongoDbAgent)
  }

  def name: String = "fpt-mongodb-agent"

  // Messages required to put data into Mongo, i.e. StoreJob(...)
  case class ComputeJobMetaData(id: String, name: String, owner: String, socketeer: String)
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
    case ComputeJobMetaData(id, name, owner, socketeer) =>
      log.debug("MongoDb Agent - {} - Received message to store in Mongo", id)
      saveComputeJobMetaData(id, name, owner, socketeer)
  }

  def saveComputeJobMetaData(id: String, name: String, owner: String, socketeer: String): Unit = {

    if (mongoDatabase.isEmpty) {
      log.warning("Mongo Database not correctly initialized. Missing Database connection")
    }

    if (mongoDatabase.isDefined) {
      // Get collection to store job meta data to
      val collection: MongoCollection[Document] =
        mongoDatabase.get.getCollection(mySettings.get.mongo.computeAgentJobsCollection)

      // Create the BSON. ID needs to be _id in the actual Document so that Mongo will treat it as defacto ID of doc
      val doc: Document = Document("_id" -> id, "name" -> name, "owner" -> owner, "socketeer" -> socketeer)

      // In the API all methods returning a Observables are “cold” streams meaning that
      // nothing happens until they are Subscribed to. This is more commonly known as lazy loading
      val observable: Observable[Completed] = collection.insertOne(doc)
      // Explicitly subscribe to activate execution of insertOne
      observable.subscribe(new Observer[Completed] {

        override def onNext(result: Completed): Unit = log.info("Compute Job MetaData inserted: {}", doc.toString())

        override def onError(e: Throwable): Unit = log.error("Compute Job MetaData failed: {}", doc.toString())

        override def onComplete(): Unit = log.info("Compute Job MetaData completed: {}", doc.toString())
      })

    }

  }
}
