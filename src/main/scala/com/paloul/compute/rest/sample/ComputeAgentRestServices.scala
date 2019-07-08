package com.paloul.compute.rest.sample

//import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import scala.util.Try
import scala.concurrent.ExecutionContextExecutor
import com.paloul.compute.agents.sample.computeagentprotocol._

// RestService defines all the routes and handlers for each request.
// This class is where you would add additional functionality concerning
// the rest API interface
class ComputeAgentRestServices(shardedAgents: ActorRef, system: ActorSystem)(implicit timeout: Timeout) {

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // All route subroutines below should be added to this definition.
  // This routes definition is publicly available to the outside
  def routes: server.Route =
    computeAgentPrintPath ~ computeAgentRepeat ~ computeAgentDoWork

  //------------------------------------------------------------------------//
  // Begin API Routes
  // The routes below utilize the implicit timeout carried over from class instantiation
  //------------------------------------------------------------------------//

  // API handler for /api/v1/compute/{id}/print_path
  private def computeAgentPrintPath = {
    get {
      pathPrefix("api" / "v1" / "compute" / UniqueIdString / "print_path") { id =>
        pathEndOrSingleSlash {
          // We need to wrap messages sent to our cluster shard with ShardedEnvelope as the
          // system needs to know how to direct messages to each final destination/actor
          shardedAgents ! ShardedEnvelope(id).withPrintPath(PrintPath())
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/compute/{id}/repeat_me
  private def computeAgentRepeat = {
    get {
      pathPrefix("api" / "v1" / "compute" / UniqueIdString / "repeat_me") { id =>
        pathEndOrSingleSlash {
          // We need to wrap messages sent to our cluster shard with ShardedEnvelope as the
          // system needs to know how to direct messages to each final destination/actor
          shardedAgents ! ShardedEnvelope(id).withRepeatMe(RepeatMe("Hello, there!"))
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/compute/{id}/do_work
  private def computeAgentDoWork = {
    get {
      pathPrefix("api" / "v1" / "compute" / UniqueIdString / "do_work") { id =>
        pathEndOrSingleSlash {
          shardedAgents ! ShardedEnvelope(id).withDoWork(DoWork())
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
