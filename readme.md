Compute Cluster 
===============
A general use compute cluster that is slowly being built out. Written in Scala and utilizing the Akka Actor 
Framework. It is designed to be highly distributed and scaled across multiple nodes. Work in progress but being
actively developed every day. 
___

### __Running the Cluster__

##### __**Instructions to run on developer laptops**__ 
In general, you start an *sbt* based application with *sbt run*. You can pass in any environment variables via the 
**-D** option
````
sbt run
````
Examples with **-D** environment variable
````
sbt -DHTTP.PORT=5001 -DAKKA.PORT=2552 run
````
````
sbt -Djava.library.path=/usr/local/Cellar/hadoop/3.1.1/lib/native/osx run
```` 

##### List of relevant environment variables
 
* **-D HTTP.PORT**
    * Allows to redefine the port Akka HTTP services start and listen on. This environment variable is useful when testing
    on the same machine using a different terminal window/tab. Default is set to 5000
* **-D AKKA.PORT**
    * Allows to redefine the port Akka uses to communicate. Each Akka application requires its own communication channel.
    Redefining the port here allows you to run multiple instances on the same machine for testing on localhost. The default
    set to 2551. 
* **-D java.library.path=/usr/local/Cellar/hadoop/3.1.1/lib/native/osx**
    * The environment variable above is necessary if you want to use  the native Hadoop libraries for your platform. The Java 
    Library Path should point to your Hadoop installation path and have the native libraries compiled and ready for use.
    [*The native libraries are not needed to run, they just speed up Hadoop access*]