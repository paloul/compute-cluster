package ai.beyond.compute.rest.sample

import ai.beyond.compute.agents.sample.{ComputeAgent, ComputeAgentJsonSupport}
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.util.Timeout

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.Try

// RestService defines all the routes and handlers for each request.
// This class is where you would add additional functionality concerning
// the rest API interface
class ComputeAgentRestServices(agents: ActorRef, system: ActorSystem)(implicit timeout: Timeout) extends ComputeAgentJsonSupport {

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // All route subroutines below should be added to this definition.
  // This routes definition is publicly available to the outside
  def routes: server.Route =
    computeAgentPrintPath ~ computeAgentHello ~ computeJobInitiate ~ computeJobState ~ computeJobCancel ~
      computeJobStates

  //------------------------------------------------------------------------//
  // Begin API Routes
  // The routes below utilize the implicit timeout carried over from class instantiation
  //------------------------------------------------------------------------//

  // API handler for /v1/api/compute/job/{id}/path
  private def computeAgentPrintPath = {
    get {
      pathPrefix("v1" / "api" / "compute" / "job" / UniqueIdString / "path") { id =>
        pathEndOrSingleSlash {
          agents ! ComputeAgent.PrintPath(id)
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/compute/job/{id}/hello
  private def computeAgentHello = {
    get {
      pathPrefix("v1" / "api" / "compute" / "job" / UniqueIdString / "hello") { id =>
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
          // Here the POST call expects a JSON body as ComputeAgent.InitiateCompute message
          // entity(as[ComputeAgent.InitiateCompute]) will parse the provided JSON in the body
          // as ComputeAgent.InitiateCompute as pass it as the initiate param
          entity(as[ComputeAgent.InitiateCompute]) { initiate =>
            // TODO: Instead of creating another ComputeAgent.InitiateCompute just send
            //  the one that was parsed from the JSON in the POST body.
            val future = agents ? ComputeAgent.InitiateCompute(
              initiate.id, initiate.name, initiate.owner, initiate.socketeer)

            // Await a response from the agent that we told to the start to pass back to the
            // caller if we had a successful initiate call
            val state = Await.result(future, timeout.duration).asInstanceOf[ComputeAgent.State]

            complete(state)
          }
        }
      }
    }
  }

  // API handler for /v1/api/compute/job/{id}/cancel
  private def computeJobCancel = {
    get {
      pathPrefix("v1" / "api" / "compute" / "job" / UniqueIdString / "cancel" ) { id =>
        pathEndOrSingleSlash {
          agents ! ComputeAgent.CancelJob(id)
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/compute/job/{id}/state
  private def computeJobState = {
    get {
      pathPrefix("v1" / "api" / "compute" / "job" / UniqueIdString / "state" ) { id =>
        pathEndOrSingleSlash {
          val future = agents ? ComputeAgent.GetState(id)
          val state = Await.result(future, timeout.duration).asInstanceOf[ComputeAgent.State]

          complete(state)
        }
      }
    }
  }

  // API handler for /v1/api/compute/jobs/state
  // expects a JSON array of string IDs
  private def computeJobStates = {
    post {
      pathPrefix("v1" / "api" / "compute" / "jobs" / "state") {
        pathEndOrSingleSlash {
          entity(as[List[String]]) { jobIds =>

            // Create a list to hold our Futures
            val futures = new ListBuffer[Future[ComputeAgent.State]]

            // Loop through each jobId provided in the POST request json array
            jobIds.foreach(jobId => {
              // Submit the Ask request to the agent cluster for the particular job id
              futures += (agents ? ComputeAgent.GetState(jobId)) map {
                // Cast the result as a ComputeAgent.State as soon as its available
                _.asInstanceOf[ComputeAgent.State]
              }
            })

            // Collect all futures that we're running in parallel
            // This now becomes a Future[ListBuffer[ComputeAgent.State]]
            val futureWithList = Future.sequence(futures)

            // Wait for all results to come in then create list of ComputeAgent.State
            val states = Await.result(futureWithList, timeout.duration)

            complete(states.toList) // Return the actual ComputeAgent.State values marshalled as JSON Array
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