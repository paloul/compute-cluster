package ai.beyond.compute.rest.aira

import ai.beyond.compute.agents.aira.geo.{GeoDynamicAgent, GeoDynamicAgentJsonSupport}
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.util.Try

// RestService defines all the routes and handlers for each request.
// This class is where you would add additional functionality concerning
// the rest API interface
class AiraAgentRestServices(agents: ActorRef, system: ActorSystem)(implicit timeout: Timeout) extends GeoDynamicAgentJsonSupport {

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // All route subroutines below should be added to this definition.
  // This routes definition is publicly available to the outside
  def routes: server.Route =
    geoDynamicPrintPath ~ geoDynamicHello ~ geoDynamicStart ~ geoDynamicCancel ~ geoDynamicState

  //------------------------------------------------------------------------//
  // Begin API Routes
  // The routes below utilize the implicit timeout carried over from class instantiation
  //------------------------------------------------------------------------//

  ////////////////
  // Geo Routes //
  ////////////////

  ///////////////////////
  // GeoDynamic Routes //
  ///////////////////////

  // API handler for /v1/api/aira/geo/dynamic/job/{id}/path
  private def geoDynamicPrintPath = {
    get {
      pathPrefix("v1" / "api" / "aira" / "geo"/ "dynamic" / "job" / UniqueIdString / "path") { id =>
        pathEndOrSingleSlash {
          agents ! GeoDynamicAgent.PrintPath(id)
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/aira/geo/dynamic/job/{id}/hello
  private def geoDynamicHello = {
    get {
      pathPrefix("v1" / "api" / "aira" / "geo" / "dynamic" / "job" / UniqueIdString / "hello") { id =>
        pathEndOrSingleSlash {
          agents ! GeoDynamicAgent.HelloThere(id, "Hello, there!")
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/aira/geo/dynamic/job/{id}/start
  private def geoDynamicStart = {
    post {
      pathPrefix("v1" / "api" / "aira" / "geo" / "dynamic" / "job" / "start") {
        pathEndOrSingleSlash {
          // Here the POST call expects a JSON body as GeoDynamicAgent.Start message
          // entity(as[GeoDynamicAgent.Start]) will parse the provided JSON in the body
          // as GeoDynamicAgent.Start as pass it as the initiate param
          entity(as[GeoDynamicAgent.Start]) { body =>
            // TODO: Instead of creating another GeoDynamicAgent.Start, just send
            //  the one that was parsed from the JSON in the POST body.
            val future = agents ? GeoDynamicAgent.Start(body.id, body.prodPath, body.owcPath,
              body.faultPath, body.perfPath, body.trajPath, body.geoMeanPath)

            // Await a response from the agent that we told to the start to pass back to the
            // caller if we had a successful initiate call
            val state = Await.result(future, timeout.duration).asInstanceOf[GeoDynamicAgent.State]

            complete(state)
          }
        }
      }
    }
  }

  // API handler for /v1/api/aira/geo/dynamic/job/{id}/cancel
  private def geoDynamicCancel = {
    post {
      pathPrefix("v1" / "api" / "aira" / "geo" / "dynamic" / "job" / UniqueIdString / "cancel") { id =>
        pathEndOrSingleSlash {
          agents ! GeoDynamicAgent.CancelJob(id)
          complete(OK)
        }
      }
    }
  }

  // API handler for /v1/api/aira/geo/dynamic/job/{id}/state
  private def geoDynamicState = {
    get {
      pathPrefix("v1" / "api" / "aira" / "geo" / "dynamic" / "job" / UniqueIdString / "state") { id =>
        pathEndOrSingleSlash {
          val future = agents ? GeoDynamicAgent.GetState(id)
          val state = Await.result(future, timeout.duration).asInstanceOf[GeoDynamicAgent.State]
          complete(state)
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
