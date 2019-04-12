# Compute Cluster #

##### Instructions to run on developer laptops ##### 
In general, you start an *sbt* based application with *sbt run*. You can pass in any environment variables via the 
**-D** option
````
sbt -Djava.library.path=/usr/local/Cellar/hadoop/3.1.1/lib/native/osx run
```` 
The environment variable above is necessary if you want to use  the native Hadoop libraries for your platform. The Java 
Library Path should point to your Hadoop installation path and have the native libraries compiled and ready for use.
[*The native libraries are not needed to run, they just speed up Hadoop access*]