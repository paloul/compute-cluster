package ai.beyond.compute.modules.image.segmentation


import org.nd4j.linalg.api.ndarray._
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.indexing.NDArrayIndex

import scala.collection.parallel.mutable.ParArray


class SLIC (
             matrix: INDArray,
             dimensions: (Int, Int, Int, Int),
             superPixelSize: Int = 20,
             _compactness: Float = Float.MinValue,
             maxIteration: Int = 10,
             minChangePerIteration: Float = 0.00001f,
             clusterNormalization: Boolean = false,
             _minSuperSize: Int = -1
           ) (implicit logger: akka.event.LoggingAdapter) {

  // Cache the logger provided implicitly
  private val log = logger

  // Extract the x.y.z from dimensions for easier accessibility/readability
  private val xDimSize: Int = dimensions._1
  private val yDimSize: Int = dimensions._2
  private val zDimSize: Int = dimensions._3
  private val sDimSize: Int = dimensions._4 // Num properties stored in 4th dimension

  private val dx: Array[Float] = Array(-1f, 1f, 0f, 0f, 0f, 0f)
  private val dy: Array[Float] = Array(0f, 0f, -1f, 1f, 0f, 0f)
  private val dz: Array[Float] = Array(0f, 0f, 0f, 0f, -1f, 1f)

  // Validate super pixel size against each axis size
  private val xS = if (xDimSize < superPixelSize) xDimSize else superPixelSize
  private val yS = if (yDimSize < superPixelSize) yDimSize else superPixelSize
  private val zS = if (zDimSize < superPixelSize) zDimSize else superPixelSize

  // Setup min super size, check if -1 provided
  private val minSuperSize = if (_minSuperSize >= 0) _minSuperSize else (xS * yS * zS) / 2

  // Setup compactness, check if Min Value passed
  private val compactness: Float =
    if (superPixelSize == Float.MinValue) superPixelSize else _compactness

  // Defined in SLIC implementation paper
  private val ivtwt = 1.0f / ((superPixelSize / compactness) * (superPixelSize / compactness))

  // Create a parallel array to hold indices of all possible voxels in the matrix
  private val voxelPointIndices: ParArray[(Int, Int, Int)] =
    initMatrixIndices((xDimSize, yDimSize, zDimSize))

  // Starting Super Center coordinates. Initialized in main segments function
  private val centerCoords: ParArray[((Int, Int, Int), Int)] =
    initSuperCenters((xDimSize, yDimSize, zDimSize), superPixelSize)

  /**
    * Main entry point to get segments of matrix. The only public function on this class.
    * @param clusters The INDArray of same size as input value matrix to hold cluster labels
    * @return An ND4J INDArray representing the world with cluster labels at each voxel point
    */
  def segments (clusters: INDArray): INDArray = {

    log.info("Starting segmentation process...")

    // Introduce any checks here, add the functions calls to the list
    // Each function call will be evaluated and results stored in checks list
    val checks = List[Boolean](
      checkDimensions(),
      checkDimensionSizes(),
      verifyValueAndClustersMatrixSizes(matrix.shape(), clusters.shape())
    )

    // Loop through the checks list and make sure everything passed
    // If checks passed then execute the core _getSegments func
    // list.forall(identity) will fail on first instance of FALSE
    if (checks.forall(identity)) {

      // init the beginning super centers
      //centerCoords = initSuperCenters((xDimSize, yDimSize, zDimSize), superPixelSize)

      // Calculate clusters and return the same INDArray that was given to us
      // to store the cluster labels
      calculateClusters(clusters)

    } else {
      // If any checks failed then reply back with empty INDArray
      log.error("SLIC Parameter Checks failed. Stopping and cleaning up.")
      // Cleanup the original clusters matrix that was given to us
      clusters.cleanup()
      // Return an empty nd4j indarray to mark failure
      Nd4j.empty()
    }
  }

  private def initMatrixIndices(dim: (Int, Int, Int)): ParArray[(Int, Int, Int)] = {
    log.info("Initializing Voxel Indices...")

    val out = for {
      x_s <- 0 until xDimSize;
      y_s <- 0 until yDimSize;
      z_s <- 0 until zDimSize
    } yield {
      (x_s, y_s, z_s)
    }

    log.info("Voxel Indices Initialized")

    out.toParArray
  }

  /**
    * Initializes superpixel segment centers to a uniform grid based on dimensions
    * @param dims Tuple of Ints representing dimensions as (x,y,z)
    * @param gridInterval Initial spacing of super pixels placed in the 3D grid
    * @return Array of Int tuples, representing (x,y,z)
    */
  private def initSuperCenters(dims: (Int, Int, Int),
                               gridInterval: Int): ParArray[((Int, Int, Int), Int)] = {
    import scala.math.floor
    import scala.math.round

    log.info("Initializing Super Centers...")

    // x -> tuple._1
    // y -> tuple._2
    // z -> tuple._3

    val xStart: Int = if (dims._1 <= gridInterval) floor(dims._1 / 2).toInt else round(gridInterval / 2f)
    val yStart: Int = if (dims._2 <= gridInterval) floor(dims._2 / 2).toInt else round(gridInterval / 2f)
    val zStart: Int = if (dims._3 <= gridInterval) floor(dims._3 / 2).toInt else round(gridInterval / 2f)

    val out = for {
      x_s <- xStart until dims._1 by gridInterval;
      y_s <- yStart until dims._2 by gridInterval;
      z_s <- zStart until dims._3 by gridInterval
    } yield {
      (x_s, y_s, z_s)
    }

    log.info("Super Centers Initialized")

    out.toParArray.zipWithIndex
  }

  /**
    * Super pixel centers need to avoid initialization on a clear edge. Perturb the centers
    * to the lowest 'discrete' gradient in a small radius/cube around the original init.
    * @param centers A parallel Array of (int,int,int) representing grid centers in matrix
    * @param adjustBy The buffer of the sphere around center point to search for low contrast
    * @return Modified list of Centers as [(int, int, int)]
    */
  private def adjustSuperCentersToLowContrast(
                               centers: ParArray[(Int, Int, Int)],
                               adjustBy: Int = 3): ParArray[(Int, Int, Int)] = {

    log.info("Adjusting Super Centers to Low Contrast...")

    centers.map( c => {
      val xC: Int = c._1
      val yC: Int = c._2
      val zC: Int = c._3

      var maxScore: Double = 0.0f
      var bestMove: (Int, Int, Int) = (0, 0, 0)

      // Get all other points surrounding each center -adjustBy to adjustBy out
      for {

        dx <- (-adjustBy) until adjustBy;
        dy <- (-adjustBy) until adjustBy;
        dz <- (-adjustBy) until adjustBy

      } {

        // Retrieve scalar value from matrix given points around the center and determine
        // if its the best move to make.
        if (checkBounds((xC + dx, yC + dy, zC + dz), (xDimSize, yDimSize, zDimSize))) {
          var difSum: Double = 0.0

          // This should give us the vector stored in the 4th dimension
          // Addition of c+d so that we get the point around the center
          // Look above in the for(), we do this for all points around center
          // in a [adjustBy] radius out
          val currFeatureVector = matrix.get(
            NDArrayIndex.point(xC+dx),
            NDArrayIndex.point(yC+dy),
            NDArrayIndex.point(zC+dz),
            NDArrayIndex.all())

          // This is going to give us a 3d box surrounding the current point
          val surroundingFeatureVectors = matrix.get(
            NDArrayIndex.interval(xC+dx-1,xC+dx+1),
            NDArrayIndex.interval(yC+dy-1,yC+dy+1),
            NDArrayIndex.interval(zC+dz-1,zC+dz+1),
            NDArrayIndex.all())
          // Get the number of the tensors in the 4th dimension in the sliced matrix
          // of surrounding feature vectors. Should equal 8 if adjustBy=default=3
          val numSurrFeatureVectors: Int =
            surroundingFeatureVectors.tensorssAlongDimension(3).toInt

          // calculate the distance of the features at selected center and its surrounding
          // points, i to num of surrounding points (feature vectors)
          for( i <- 0 until numSurrFeatureVectors ) { // until is 0->(numSurrFeatureVectors-1)

            difSum += distanceFunction(
              currFeatureVector,
              surroundingFeatureVectors.tensorAlongDimension(i, 3))

          }

          // NOTE: UMMM DifSum is additive and maxScore is starting at 0.
          //  How can difSum ever be less than maxScore? The distance function
          //  will always return positive.
          // NOTE: This method might be avoidable, but at least it led to some
          //  good insight in how to remove tensors from the 4th dimension and
          //  run nd4j native computation on the vectors direct instead of bringing into JVM

          if (difSum < maxScore) {
            maxScore = difSum
            bestMove = (dx, dy, dz)
          }
        }
      }

      // Return the new center point, modified by best move from original
      (xC + bestMove._1, yC + bestMove._2, zC + bestMove._3)
    })
  }

  /**
    * Compute the distance of given point to the center of a cluster
    * @param point The point in space comparing to a center
    * @param center The center tuple point from our center coords
    * @return The distance of point from center of cluster as Float
    */
  private def pointDistanceFromCluster(point: (Int, Int, Int), center: (Int, Int, Int)): Double = {

    // Get the Center Coordinate the feature vector from the source matrix
    val centerFeaturesVector = matrix.get(
      NDArrayIndex.point(center._1),
      NDArrayIndex.point(center._2),
      NDArrayIndex.point(center._3),
      NDArrayIndex.all())

    // Get the features for the point from the source matrix
    val pointFeaturesVector = matrix.get(
      NDArrayIndex.point(point._1),
      NDArrayIndex.point(point._2),
      NDArrayIndex.point(point._3),
      NDArrayIndex.all())

    // Calculate the distances of space and features. Use of ivtwt coming from SLIC implementation
    val distSpace = distanceFunction(point, center) * ivtwt
    val distFeatures = distanceFunction(centerFeaturesVector, pointFeaturesVector)

    // Return the distance calculations
    distFeatures + distSpace
  }

  /**
    * The main private function that does the SLIC algorithm
    * @param clusters The INDArray of same size as input value matrix to hold cluster labels
    * @return 3D INDArray of integers indicating segment labels
    */
  private def calculateClusters (clusters: INDArray): INDArray = {

    log.info("Calculating Clusters...")

    // Initiate variables that are used in algorithm
    val dims = (xDimSize, yDimSize, zDimSize)
    val lastKnownDistanceOfPointFromCenter = Nd4j.valueArrayOf(
      Array(xDimSize, yDimSize, zDimSize), Float.MaxValue)

    var otherHalt = 0
    var lastChange = Double.MaxValue

    var once = true

    //while (otherHalt < maxIteration & lastChange > minChangePerIteration) {
    while(once) {

      once = false

      // Use the indices of the centers to retrieve them,
      // since we use the center index as the cluster label index
      val t0_clusterAssign = System.nanoTime()
      centerCoords.foreach { case(c, ci) =>

        val centerVoxel: INDArray = matrix.get(
          NDArrayIndex.point(c._1),
          NDArrayIndex.point(c._2),
          NDArrayIndex.point(c._3)
        )

        // Setup the starting and ending positions for x.y.z.
        // Find points out double super pixel size for each center
        val xStart: Int = if (c._1 - 2 * superPixelSize < 0) 0 else c._1 - 2 * superPixelSize
        val yStart: Int = if (c._2 - 2 * superPixelSize < 0) 0 else c._2 - 2 * superPixelSize
        val zStart: Int = if (c._3 - 2 * superPixelSize < 0) 0 else c._3 - 2 * superPixelSize
        val xEnd: Int = if (c._1 + 2 * superPixelSize > xDimSize-1) xDimSize-1 else c._1 + 2 * superPixelSize
        val yEnd: Int = if (c._2 + 2 * superPixelSize > yDimSize-1) yDimSize-1 else c._2 + 2 * superPixelSize
        val zEnd: Int = if (c._3 + 2 * superPixelSize > zDimSize-1) zDimSize-1 else c._3 + 2 * superPixelSize

        // Get all points around the center point within start and end points
        val voxels = matrix.get(
          NDArrayIndex.interval(xStart, xEnd),
          NDArrayIndex.interval(yStart, yEnd),
          NDArrayIndex.interval(zStart, zEnd))

        // Get the number of voxels we have given the start and end points
        val numVoxelFeatures: Int = voxels.tensorssAlongDimension(3).toInt
        log.info("Number of Voxel Features for C({}) - {}", ci, numVoxelFeatures)
        log.info("Center Shape " + centerVoxel.shape().mkString(","))
        log.info(centerVoxel.toString)
        log.info("Tensor Shape " + voxels.tensorAlongDimension(0, 3).shape().mkString(","))

//        for( i <- 0 until numVoxelFeatures ) { // until is 0->(numVoxelFeatures-1)
//
//          val distance = distanceFunction(
//            centerVoxel,
//            voxels.tensorAlongDimension(i, 3))
//
//        }

        // NOTE: For now looping through all points around the center and treating each
        //  voxel separately. Maybe there is a way to get the list of voxels with
        //  tensorssAlongDimension API. But i've realized we need to have the x.y.z of the
        //  voxel in order to save the cluster label in the clusters matrix. Think about this.
        //  Access can be faster if we don't have to loop through in JVM but push execution
        //  out to ND4j and the underlying matrix library (GPU). We can get the feature vectors
        //  with the tensors along dimension api but then we lose the x.y.z indices of where the
        //  feature vector came from.
//        for (
//
//          xVoxel <- c._1 - 2 * superPixelSize until c._1 + 2 * superPixelSize;
//          yVoxel <- c._2 - 2 * superPixelSize until c._2 + 2 * superPixelSize;
//          zVoxel <- c._3 - 2 * superPixelSize until c._3 + 2 * superPixelSize
//
//        ) {
//          // Create the voxel as a tuple of the current point surrounding center
//          val voxelTuple = (xVoxel, yVoxel, zVoxel)
//          val voxelArray = Array(xVoxel, yVoxel, zVoxel)
//
//          // Check if voxel is in bounds before doing anything else
//          if (checkBounds(voxelTuple, dims)) {
//
//            // Calculate distance (features and space) of point from center of cluster
//            val curVoxelDist = pointDistanceFromCluster(voxelTuple, c)
//            val lastDistance =
//              lastKnownDistanceOfPointFromCenter.getFloat(voxelArray)
//
//            if(lastDistance > curVoxelDist) {
//
//              lastKnownDistanceOfPointFromCenter.putScalar(voxelArray, curVoxelDist)
//              clusters.putScalar(voxelArray, ci)
//
//            }
//          }
//        }
      }
      val t1_clusterAssign = System.nanoTime()
      log.info("[SLIC] Round of cluster assignment elapsed time: {} (s)",
        (t1_clusterAssign - t0_clusterAssign) / 1E9)

      // TODO: Continue Here
    }

    // cleanup the last known array
    lastKnownDistanceOfPointFromCenter.cleanup()

    // Return the clusters matrix. Cluster Assignments is a matrix of same
    // dimensions and size of the original provided matrix. But the scalar values
    // represent a label of each cluster the voxel point was associated with.
    // The clusters matrix is provided by the caller of this class as the caller
    // needs to make use of the matrix to identify and process clusters further.
    // That way they can destroy the matrix when appropriate instead of this class.
    clusters
  }

  /**
    * Calculates distance given two tuples with x.y.z integers in a space
    * @param x First point
    * @param y Second point
    * @return Distance as double
    */
  private def distanceFunction(x:(Int, Int, Int), y:(Int, Int, Int)): Double = {
    import scala.math.sqrt
    import scala.math.pow

    sqrt(pow(x._1 - y._1, 2) + pow(x._2 - y._2, 2) + pow(x._3 - y._3, 2))
  }

  /**
    * Calculates and returns distance given two INDArrays from nd4j. Utilizes
    * distance function from nd4j library
    * @param x First INDArray
    * @param y Second INDArray
    * @return Float value of distance between the two INDArrays
    */
  private def distanceFunction(x:INDArray, y:INDArray): Double = {

    x.distance1(y)
  }

  /**
    * Check Bounds of given point within 0 to dims provided
    * @param point Int Tuple of x,y,z of point to check
    * @param dims Int Tuple of 3 axis dimensions to check if point is in.
    * @return True if within bounds
    */
  private def checkBounds(point: (Int, Int, Int),
                          dims: (Int, Int, Int)): Boolean = {

    point._1 >= 0 && point._1 < dims._1 &&
      point._2 >= 0 && point._2 < dims._2 &&
      point._3 >= 0 && point._3 < dims._3
  }

  /**
    * Checks Bounds of given point within min dims to max dims provided
    * @param point Int Tuple of x,y,z of point to check
    * @param minDims Min Dimensions to check within
    * @param maxDims Max Dimensions to check within
    * @return True if within bounds
    */
  private def checkBounds(point: (Int, Int, Int),
                          minDims: (Int, Int, Int),
                          maxDims: (Int, Int, Int)): Boolean = {

    // Remember last statement in defs for Scala return automatically
    point._1 >= minDims._1 && point._1 < maxDims._1 &&
      point._2 >= minDims._2 && point._2 < maxDims._2 &&
      point._3 >= minDims._3 && point._3 < maxDims._3
  }

  /**
    * Checks Dimensions of the given matrix
    * @return True if matrix dimensions equal 4, false for anything else
    */
  private def checkDimensions(): Boolean = {
    if (matrix.shape().length == 4) {
      true
    } else {
      log.warning("SLIC expects Matrix with FOUR dimensions. " +
        "Given Matrix only has [{}]", matrix.shape().length)
      false
    }
  }

  /**
    * Checks the actual size of the matrix against the dimensions provided by client
    * @return
    */
  private def checkDimensionSizes(): Boolean = {
    if ((xDimSize > 0 && matrix.shape().apply(0) == xDimSize) &&
        (yDimSize > 0 && matrix.shape().apply(1) == yDimSize) &&
        (zDimSize > 0 && matrix.shape().apply(2) == zDimSize)) {
      true
    } else {
      log.warning("Matrix shape does not match provided" +
        "Matrix has Shape:[{}], while provided is {}",
        matrix.shape().mkString(","),
        "["+xDimSize+","+yDimSize+","+zDimSize+","+sDimSize+"]")
      false
    }
  }

  /**
    * Compares the sizes of the value matrix and the clusters matrix provided as they
    * need to match in dimensions and sizes
    * @param matrix 4-dimensional NDArray holding values
    * @param clusters 3-dimensional INDArray holding cluster labels
    * @return
    */
  private def verifyValueAndClustersMatrixSizes(
                   matrix: Array[Long], clusters: Array[Long]): Boolean = {

    // x.y.z sizes need to match only, ignore 4th dimension of matrix
    if (matrix.apply(0) == clusters.apply(0) &&
      matrix.apply(1) == clusters.apply(1) &&
      matrix.apply(2) == clusters.apply(2)) {
      true
    } else {
      log.warning("Matrix and Clusters shape do not match" +
        "Matrix has Shape:[{}], while Clusters has [{}]",
        matrix.mkString(","),
        clusters.mkString(","))
      false
    }

  }
}
