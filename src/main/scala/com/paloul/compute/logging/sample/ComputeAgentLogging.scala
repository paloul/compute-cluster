package com.paloul.compute.logging.sample

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.paloul.compute.agents.sample.ComputeAgent

// Extend the ActorLogging Trait with ComputeAgentLogging trait
// to add additional loggers for Job Status and Job Results
// Restricted only to the ComputeAgent class
trait ComputeAgentLogging { this: ComputeAgent =>

  private val _logger = LoggerFactory.getLogger(ComputeAgent.getClass)

  /*
    NOTE: You can add any custom log adapter here. This would be
      specific for ComputeAgents since if you can only add this trait to
      ComputeAgents. This will allow you to create multiple log adapters
      that utilize different logback.xml loggers/appenders.
      Look below to the commented out code. You can also have access
      to the underlying default 'log' variable created by the base
      ActorLogging trait that this one extends.
   */

  def log: Logger = { _logger }

}
