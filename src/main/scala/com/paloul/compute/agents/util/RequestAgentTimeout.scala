package com.paloul.compute.agents.util

import akka.util.Timeout
import com.paloul.compute.Settings

trait RequestAgentTimeout {
  import scala.concurrent.duration.FiniteDuration

  // Timeout handler for HTTP responses
  def requestAgentTimeout(settings: Settings): Timeout = {
    // Get the request-timeout duration from the Settings class
    val d = settings.cluster.agentTimeout

    // Return the duration as a Timeout
    FiniteDuration(d.length, d.unit)
  }
}
