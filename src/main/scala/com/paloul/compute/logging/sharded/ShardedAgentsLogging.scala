package com.paloul.compute.logging.sharded

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.paloul.compute.sharded.ShardedAgents

trait ShardedAgentsLogging { this: ShardedAgents =>

  private val _logger = LoggerFactory.getLogger(ShardedAgents.getClass)

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
