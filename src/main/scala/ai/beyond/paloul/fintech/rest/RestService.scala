package ai.beyond.paloul.fintech.rest

import ai.beyond.paloul.fintech.agents.{AlgorithmAgent, StockPriceAgent, UserAgent, WorkAgent}
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
class RestService(agents: ActorRef, system: ActorSystem) (implicit timeout: Timeout) {

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // All route subroutines below should be added to this definition.
  // This routes definition is publicly available to the outside
  def routes: server.Route =
    stockPriceAgentPrintPath ~ stockPriceAgentHello ~
      algorithmAgentPrintPath ~ algorithmAgentHello ~
      workAgentHello ~ workAgentPrintPath ~
      userAgentHello ~ userAgentIncrement ~ userAgentDecrement

  //------------------------------------------------------------------------//
  // Begin API Routes
  // The routes below utilize the implicit timeout carried over from class instantiation
  //------------------------------------------------------------------------//

  // API handler for api/stock/agent/{id}/path
  private def stockPriceAgentPrintPath = {
    get {
      pathPrefix("api" / "stock" / "agent" / UniqueIdString / "path") { userId =>
        pathEndOrSingleSlash {
          agents ! StockPriceAgent.PrintPath(userId)
          complete(OK)
        }
      }
    }
  }

  // API handler for api/stock/agent/{id}/path
  private def stockPriceAgentHello = {
    get {
      pathPrefix("api" / "stock" / "agent" / UniqueIdString / "hello") { userId =>
        pathEndOrSingleSlash {
          agents ! StockPriceAgent.HelloThere(userId, "Hello, there!")
          complete(OK)
        }
      }
    }
  }

  // API handler for api/algorithm/agent/{id}/path
  private def algorithmAgentPrintPath = {
    get {
      pathPrefix("api" / "algorithm" / "agent" / UniqueIdString / "path") { userId =>
        pathEndOrSingleSlash {
          agents ! AlgorithmAgent.PrintPath(userId)
          complete(OK)
        }
      }
    }
  }

  // API handler for api/algorithm/agent/{id}/hello
  private def algorithmAgentHello = {
    get {
      pathPrefix("api" / "algorithm" / "agent" / UniqueIdString / "hello") { userId =>
        pathEndOrSingleSlash {
          agents ! AlgorithmAgent.HelloThere(userId, "Hello, there!")
          complete(OK)
        }
      }
    }
  }

  // API handler for api/work/agent/{id}/path
  private def workAgentPrintPath = {
    get {
      pathPrefix("api" / "work" / "agent" / UniqueIdString / "path") { userId =>
        pathEndOrSingleSlash {
          agents ! WorkAgent.PrintPath(userId)
          complete(OK)
        }
      }
    }
  }

  // API handler for api/work/agent/{id}/hello
  private def workAgentHello = {
    get {
      pathPrefix("api" / "work" / "agent" / UniqueIdString / "hello") { userId =>
        pathEndOrSingleSlash {
          agents ! WorkAgent.HelloThere(userId, "Hello, there!")
          complete(OK)
        }
      }
    }
  }

  // API handler for api/user/agent/{id}/hello
  private def userAgentHello = {
    get {
      pathPrefix("api" / "user" / "agent" / UniqueIdString / "hello") { userId =>
        pathEndOrSingleSlash {
          agents ! UserAgent.HelloThere(userId, "Hello, there!")
          complete(OK)
        }
      }
    }
  }

  private def userAgentIncrement = {
    get {
      pathPrefix("api" / "user" / "agent" / UniqueIdString / "increment") { userId =>
        pathEndOrSingleSlash {
          agents ! UserAgent.Increment(userId, 1)
          complete(OK)
        }
      }
    }
  }

  private def userAgentDecrement = {
    get {
      pathPrefix("api" / "user" / "agent" / UniqueIdString / "decrement") { userId =>
        pathEndOrSingleSlash {
          agents ! UserAgent.Decrement(userId, -1)
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
