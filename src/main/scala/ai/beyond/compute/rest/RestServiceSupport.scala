package ai.beyond.compute.rest

import ai.beyond.compute.Settings
import ai.beyond.compute.rest.aira.{AiraAgentRestServices, AiraSampleOneAgentRestServices}
import ai.beyond.compute.rest.sample.ComputeAgentRestServices
import akka.util.Timeout

import scala.util.{Failure, Success}
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import akka.stream.ActorMaterializer

import scala.concurrent.{ExecutionContextExecutor, Future}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.event.Logging

// This trait is merely support to setup the Rest Services
// Instantiates and uses the RestService class underneath
trait RestServiceSupport extends RequestTimeout {

  // Basic classes to help identify what is going on when we quit
  class FailedToBind
  class ShutdownRequested

  def startRestService(agents: ActorRef, settings: Settings) (implicit system: ActorSystem): Unit = {

    // Create the log adapter to use below, system.name here coincides to the name given in Main
    // when the ActorSystem was created.
    val log = Logging(system.eventStream, system.name)

    // Make timeout implicit, carry over to RestService which the route handlers will use
    implicit val timeout: Timeout = requestTimeout(settings)

    // Needed for the future flatMap/onComplete in the end
    // HTTP BindHandler relies on execution context to dispatch handlers
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    // Create each Rest Service class and get its routes
    // Each RestService class defines the routes and how to deal with each request, i.e. forward to agents
    val airaSampleRestApiRoutes = new AiraSampleOneAgentRestServices(agents, system).routes
    val computeRestApiRoutes = new ComputeAgentRestServices(agents, system).routes
    val airaRestApiRoutes = new AiraAgentRestServices(agents, system).routes
    /* INSTANTIATE ADDITIONAL REST API ROUTES HERE AFTER CREATION OF NEW SUPPORT CLASS */

    // Combine all the routes from underlying agent rest services in to one
    val routes = airaSampleRestApiRoutes ~ computeRestApiRoutes ~ airaRestApiRoutes /* APPEND ANY NEW ROUTES HERE WITH THE ~ SYMBOL */

    val host = settings.http.host // Host address to bind to
    val port = settings.http.port // Port address to bind to

    // Bind and create handler for HTTP requests. Returns a Future expecting ServerBinding
    // bindAndHandle uses the implicit materializer from above.
    val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(routes, host, port)

    // Let the user know if HTTP binding was a success or failure.
    // If failure then terminate the whole system
    bindingFuture onComplete {
      case Success(serverBinding) => {
        log.info("Rest API bound to {}", serverBinding.localAddress)
      }

      case Failure(ex) => {
        log.error(ex, "Failed to bind to {}:{}!", host, port)

        CoordinatedShutdown(system).run(new FailedToBind with CoordinatedShutdown.Reason)
      }
    }

    // GKP: Disabled the following exit handler as caused issues with running inside Docker
//    // Capture the Enter key here to initiate the shutdown process properly
//    log.info("Press the 'enter' key to initiate shutdown...")
//    scala.io.StdIn.readLine() // ReadLine will wait until a new line is submitted
//    // Unbind from the port and shut down when down
//    bindingFuture
//      .flatMap(_.unbind())
//      .onComplete(_ => {
//          CoordinatedShutdown(system).run(new ShutdownRequested with CoordinatedShutdown.Reason)
//        }
//      )

  }
}

// RequestTimeout trait implements function to extract timeout value
// defined in application.conf with the help of the Settings class
// In essence, converts the defined Duration to a Timeout for use
trait RequestTimeout {
  import scala.concurrent.duration.FiniteDuration

  // Timeout handler for HTTP responses
  def requestTimeout(settings: Settings): Timeout = {
    // Get the request-timeout duration from the Settings class
    val d = settings.http.requestTimeout

    // Return the duration as a Timeout
    FiniteDuration(d.length, d.unit)
  }
}
