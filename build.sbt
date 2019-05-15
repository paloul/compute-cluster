name := "ai.beyond.compute-cluster"

organization := "ai.beyond"

maintainer := "gpaloulian@beyond.ai"

version := "0.0.1"

scalaVersion := "2.11.12"

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
  val akkaPersistenceCassandra = "0.93"
  val nd4jVersion = "1.0.0-beta3"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,

    "com.typesafe.akka" %% "akka-stream" % akkaVersion,

    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,

    // https://github.com/akka/akka-persistence-cassandra
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandra,

    "com.typesafe.akka" %% "akka-remote" % akkaVersion
      exclude("io.netty", "netty"), // No need to have this as we are using Artery for Remoting

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

    // Kafka client
    "org.apache.kafka" % "kafka-clients" % "2.1.1",

    // Mongo client
    "org.mongodb.scala" %% "mongo-scala-driver" % "2.6.0",

    // kantan.csv - https://nrinaudo.github.io/kantan.csv/
    "com.nrinaudo" %% "kantan.csv" % "0.5.0",
    "com.nrinaudo" %% "kantan.csv-java8" % "0.5.0",
    "com.nrinaudo" %% "kantan.csv-generic" % "0.5.0",

    // https://deeplearning4j.org/docs/latest/deeplearning4j-config-buildtools
    "org.deeplearning4j" % "deeplearning4j-core" % nd4jVersion,
    "org.nd4j" % "nd4j-native-platform" % nd4jVersion
  )
}


// Settings for the docker image to be built
// Look into Docker support for sbt-native-packager
// To build the docker image: sbt docker:publishLocal
/*
universal:packageBin - Generates a universal zip file
universal:packageZipTarball - Generates a universal tgz file
debian:packageBin - Generates a deb
docker:publishLocal - Builds a Docker image using the local Docker server
rpm:packageBin - Generates an rpm
universal:packageOsxDmg - Generates a DMG file with the same contents as the universal zip/tgz.
windows:packageBin - Generates an MSI
 */
packageName in Docker := name.value
version in Docker := version.value
dockerBaseImage := "openjdk:8-stretch"
dockerExposedPorts in Docker := Seq(5050, 5051, 5052, 2550, 2551, 2552)
enablePlugins(JavaAppPackaging)
