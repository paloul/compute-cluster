name := "com.paloul.compute-cluster"

organization := "com.paloul"

maintainer := "george.k.paloulian@gmail.com"

version := "0.0.1"

scalaVersion := "2.13.0"

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
  val akkaVersion = "2.5.23"
  val akkaHttpVersion = "10.1.8"
  val akkaPersistenceCassandra = "0.98"

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
    "ch.qos.logback" % "logback-classic" % "1.2.3"
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
