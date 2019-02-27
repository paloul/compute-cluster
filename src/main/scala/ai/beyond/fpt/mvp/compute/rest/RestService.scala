package ai.beyond.fpt.mvp.compute.rest

import ai.beyond.fpt.mvp.compute.agents.{ComputeAgent, ComputeAgentJsonSupport}
import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout

import scala.util.Try
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._

import scala.concurrent.ExecutionContextExecutor

// RestService defines all the routes and handlers for each request.
// This class is where you would add additional functionality concerning
// the rest API interface
class RestService(agents: ActorRef, system: ActorSystem)(implicit timeout: Timeout) extends ComputeAgentJsonSupport {

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // All route subroutines below should be added to this definition.
  // This routes definition is publicly available to the outside
  def routes: server.Route =
    computeAgentPrintPath ~ computeAgentHello ~ computeJobInitiate

  //------------------------------------------------------------------------//
  // Begin API Routes
  // The routes below utilize the implicit timeout carried over from class instantiation
  //------------------------------------------------------------------------//

  // API handler for api/algorithm/agent/{id}/path
  private def computeAgentPrintPath = {
    get {
      pathPrefix("api" / "compute" / "agent" / UniqueIdString / "path") { id =>
        pathEndOrSingleSlash {
          agents ! ComputeAgent.PrintPath(id)
          complete(OK)
        }
      }
    }
  }

  // API handler for api/algorithm/agent/{id}/hello
  private def computeAgentHello = {
    get {
      pathPrefix("api" / "compute" / "agent" / UniqueIdString / "hello") { id =>
        pathEndOrSingleSlash {
          agents ! ComputeAgent.HelloThere(id, "Hello, there!")
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/compute/job/initiate
  private def computeJobInitiate = {
    post {
      pathPrefix("v1" / "api" / "compute" / "job" / "initiate") {
        pathEndOrSingleSlash {
          entity(as[ComputeAgent.InitiateCompute]) { initiate =>
            agents ! ComputeAgent.InitiateCompute(initiate.id, initiate.partition, initiate.socketeer)
            complete(OK)
          }
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
