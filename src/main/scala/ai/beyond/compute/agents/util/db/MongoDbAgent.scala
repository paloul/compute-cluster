package ai.beyond.compute.agents.util.db

import ai.beyond.compute.Settings
import ai.beyond.compute.agents.util.db.MongoMasterAgent.{MongoMessage, mySettings}
import akka.actor.{Actor, ActorLogging, Props}
import org.mongodb.scala._

object MongoDbAgent {
  def props(settings: Settings, mongoDatabase: MongoDatabase) = {
    Props(new MongoDbAgent(mongoDatabase))
  }

  def name: String = "computecluster-mongodb-agent"

  ///////////////////////
  // Messages our MongoDbAgent can receive
  ///////////////////////
  // Compute Job Meta Data message to store metadata of compute jobs to mongo
  // Remember in Scala, default parameters should be towards the end or else you need to define each param
  // in the call to the function/class specifically i.e. ComputeJobMetaData(id=idstr, name=namestr, etc)
  case class ComputeJobMetaData(id: String, name: String, owner: String, socketeer: String,
              collection: String = mySettings.get.mongo.computeAgentJobsCollection) extends MongoMessage

  // To add new capability of adding data to Mongo:
  //  1. Add a new message here as a case class
  //  2. Add the logic in the receive method to handle
  //      the case class as message and insert into mongo as you need

  ///////////////////////
}

class MongoDbAgent(val mongoDatabase: MongoDatabase) extends Actor with ActorLogging {
  // Import the companion object above to use the messages defined for us
  import MongoDbAgent._

  // self.path.name is the entity identifier (utf-8 URL-encoded)
  def id: String = self.path.name

  //------------------------------------------------------------------------//
  // Actor lifecycle
  //------------------------------------------------------------------------//
  override def preStart(): Unit = {
    log.info("MongoDb Agent - {} - starting", id)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Debugging information if agent is restarted
    log.error(reason, "MongoDb Agent restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    log.info("MongoDb Agent - {} - stopped", id)
  }
  //------------------------------------------------------------------------//
  // End Actor Lifecycle
  //------------------------------------------------------------------------//

  override def receive: Receive = {
    case ComputeJobMetaData(id, name, owner, socketeer, collection) =>
      log.debug("MongoDb Agent - {} - Received message to store in Mongo", id)
      saveComputeJobMetaData(collection, id, name, owner, socketeer)
  }

  def saveComputeJobMetaData(collection: String, id: String, name: String, owner: String, socketeer: String): Unit = {

    // Get collection to store job meta data to
    val col: MongoCollection[Document] = mongoDatabase.getCollection(collection)

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
