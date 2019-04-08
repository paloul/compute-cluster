package ai.beyond.compute.rest

import ai.beyond.compute.agents.aira.AiraSampleOneAgent
import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.Future
import scala.util.Try
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContextExecutor}

// RestService defines all the routes and handlers for each request.
// This class is where you would add additional functionality concerning
// the rest API interface
class AiraSampleOneAgentRestServices(agents: ActorRef, system: ActorSystem)(implicit timeout: Timeout) {

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // All route subroutines below should be added to this definition.
  // This routes definition is publicly available to the outside
  def routes: server.Route =
    agentPrintPath ~ agentHello

  //------------------------------------------------------------------------//
  // Begin API Routes
  // The routes below utilize the implicit timeout carried over from class instantiation
  //------------------------------------------------------------------------//

  // API handler for /v1/api/aira/job/{id}/path
  private def agentPrintPath = {
    get {
      pathPrefix("v1" / "api" / "aira" / "job" / UniqueIdString / "path") { id =>
        pathEndOrSingleSlash {
          agents ! AiraSampleOneAgent.PrintPath(id)
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/aira/job/{id}/hello
  private def agentHello = {
    get {
      pathPrefix("v1" / "api" / "aira" / "job" / UniqueIdString / "hello") { id =>
        pathEndOrSingleSlash {
          agents ! AiraSampleOneAgent.HelloThere(id, "Hello, there!")
          complete(OK)
        }
      }
    }
  }

  //------------------------------------------------------------------------//
  // End API Routes
  //------------------------------------------------------------------------//

  // This helps us extract the string id from the path and
  // convert it into a Long which is what Agent functions expect
  //private val UniqueIdSegment = Segment.flatMap(id => Try(id.toLong).toOption)
  // This one does the same functionality but returns it as a string.
  // Use this one where you intend to have Agent/Entity Ids as Strings and not Longs
  private val UniqueIdString = Segment.flatMap(id => Try(id.toString).toOption)
}
