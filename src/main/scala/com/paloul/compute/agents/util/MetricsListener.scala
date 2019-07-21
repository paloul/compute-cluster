package com.paloul.compute.agents.util

import akka.actor.{Actor, ActorLogging, Props}
import akka.cluster.Cluster
import akka.cluster.metrics.ClusterMetricsEvent
import akka.cluster.metrics.ClusterMetricsChanged
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.metrics.NodeMetrics
import akka.cluster.metrics.StandardMetrics.HeapMemory
import akka.cluster.metrics.StandardMetrics.Cpu
import akka.cluster.metrics.ClusterMetricsExtension
import com.paloul.compute.Settings

object MetricsListener {
  var settings: Option[Settings] = None

  def props(s: Settings): Props = {

    settings = Some(s)

    Props(new MetricsListener)
  }

  def name: String = "compute-cluster-metrics-listener"
}

/**
  * Metrics Listener actor that subscribes itself to the Cluster
  * Metrics Extension on start and received Cluster Metric Changed events.
  * Currently logs heap and cpu information via actor logging.
  */
class MetricsListener extends Actor with ActorLogging {
  private val selfAddress = Cluster(context.system).selfAddress
  private val extension = ClusterMetricsExtension(context.system)

  // Subscribe unto ClusterMetricsEvent events.
  override def preStart(): Unit = extension.subscribe(self)

  // Unsubscribe from ClusterMetricsEvent events.
  override def postStop(): Unit = extension.unsubscribe(self)

  override def receive: Receive = {
    case ClusterMetricsChanged(clusterMetrics) =>
      clusterMetrics.filter(_.address == selfAddress).foreach { nodeMetrics =>
        logHeap(nodeMetrics)
        logCpu(nodeMetrics)
      }

    case state: CurrentClusterState => // Ignore
  }

  /**
    * Logs Heap information via ActorLogging. Reports only used heap memory in MB
    * @param nodeMetrics
    */
  def logHeap(nodeMetrics: NodeMetrics): Unit = nodeMetrics match {
    case HeapMemory(address, timestamp, used, committed, max) =>
      log.info("Used heap: {} MB", used.doubleValue / 1024 / 1024)

    case _ => // No heap info.
  }

  /**
    * Logs CPU information via ActorLogging. Reports average system load and num processors.
    * @param nodeMetrics
    */
  def logCpu(nodeMetrics: NodeMetrics): Unit = nodeMetrics match {
    case Cpu(address, timestamp, Some(systemLoadAverage), cpuCombined, cpuStolen, processors) =>
      log.info("Load: {} ({} processors)", systemLoadAverage, processors)

    case _ => // No cpu info.
  }
}
