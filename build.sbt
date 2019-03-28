name := "ai.beyond.fpt.mvp.compute-agents"

organization := "ai.beyond"

version := "0.6.0"

scalaVersion := "2.12.8"

exportJars := true

scalacOptions ++= Seq(
  "-deprecation"
  ,"-unchecked"
  ,"-encoding", "UTF-8"
  ,"-Xlint"
  ,"-Xverify"
  ,"-feature"
  ,"-language:postfixOps"
)

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

    // Used for serialization of messages between agents
    "org.json4s" %% "json4s-native" % json4sVersion,
    "org.json4s" %% "json4s-ext" % json4sVersion,

    "org.apache.kafka" % "kafka-clients" % "2.1.1",

    "org.mongodb.scala" %% "mongo-scala-driver" % "2.6.0"
  )
}

// Settings for the docker image to be built
// Look into Docker support for sbt-native-packager
// To build the docker image: sbt docker:publishLocal
packageName in Docker := name.value
version in Docker := version.value
dockerBaseImage := "openjdk:8-stretch"
dockerExposedPorts in Docker := Seq(5050, 5051, 5052, 2550, 2551, 2552)
enablePlugins(JavaAppPackaging)
