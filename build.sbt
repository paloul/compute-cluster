name := "ai.beyond.fpt.mvp.compute-agents"

version := "0.6.0"

scalaVersion := "2.12.8"

exportJars := true

libraryDependencies ++= {
  val json4sVersion = "3.6.4"
  val akkaVersion = "2.5.20"
  val akkaHttpVersion = "10.1.7"
  val akkaPersistenceCassandra = "0.92"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,

    "com.typesafe.akka" %% "akka-stream" % akkaVersion,

    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,

    // https://github.com/akka/akka-persistence-cassandra
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandra,

    "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,

    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,

    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "ch.qos.logback" % "logback-classic" % "1.2.3",

    "org.json4s" %% "json4s-native" % json4sVersion,
    "org.json4s" %% "json4s-ext" % json4sVersion,

    "org.apache.kafka" % "kafka-clients" % "2.1.1"
  )
}
