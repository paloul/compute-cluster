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
  val ivtwt = 1.0f / ((superPixelSize / compactness) * (superPixelSize / compactness))

  // Create a new ND4j matrix of 3 dimensions the same shape as matrix, fill
  // it with -5 values as initial cluster values, i.e. -5 means no cluster assigned
  private val clusterAssignments: INDArray =
    Nd4j.valueArrayOf(Array(xDimSize, yDimSize, zDimSize), -5f)

  // Initialize starting super centers
  val centerCoords = initSuperCenters((xDimSize, yDimSize, zDimSize), superPixelSize)

  /**
    * Main entry point to get segments of matrix. The only public function on this class.
    * @return An ND4J INDArray representing the world with cluster labels at each voxel point
    */
  def segments (): INDArray = {

    log.info("Starting segmentation process...")

    // Introduce any checks here, add the functions calls to the list
    // Each function call will be evaluated and results stored in checks list
    val checks = List[Boolean](
      checkDimensions(),
      checkDimensionSizes()
    )

    // Loop through the checks list and make sure everything passed
    // If checks passed then execute the core _getSegments func
    // list.forall(identity) will fail on first instance of FALSE
    if (checks.forall(identity)) {

      calcClusterAssignments()

    } else {
      // If any checks failed then reply back with empty INDArray
      log.error("SLIC Parameter Checks failed. Stopping.")
      Nd4j.empty()
    }
  }

  /**
    * Initializes superpixel segment centers to a uniform grid based on dimensions
    * @param dims Tuple of Ints representing dimensions as (x,y,z)
    * @param gridInterval Initial spacing of super pixels placed in the 3D grid
    * @return Array of Int tuples, representing (x,y,z)
    */
  private def initSuperCenters(dims: (Int, Int, Int),
                               gridInterval: Int): ParArray[(Int, Int, Int)] = {
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

    out.toArray.par
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

    centers.map(c => {
      val xC: Int = c._1
      val yC: Int = c._2
      val zC: Int = c._3

      var maxScore: Float = 0.0f
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
          var difSum: Float = 0.0f

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
    * @param ci The index of the center from our center coords par array to compare to
    * @return The distance of point from center of cluster as Float
    */
  private def pointDistanceFromCluster(point: (Int, Int, Int), ci: Int): Double = {

    // Get the Center Coordinate x.y.z values and the feature vector from the source matrix
    val c = centerCoords(ci)
    val xC: Int = c._1
    val yC: Int = c._2
    val zC: Int = c._3
    val centerFeaturesVector = matrix.get(
      NDArrayIndex.point(xC),
      NDArrayIndex.point(yC),
      NDArrayIndex.point(zC),
      NDArrayIndex.all())

    // Get the features for the point from the source matrix
    val pointFeaturesVector = matrix.get(
      NDArrayIndex.point(point._1),
      NDArrayIndex.point(point._2),
      NDArrayIndex.point(point._3),
      NDArrayIndex.all())

    // Calculate the distances of space and features. Use of ivtwt coming from SLIC implementation
    val distSpace = distanceFunction(point, c) * ivtwt
    val distFeatures = distanceFunction(centerFeaturesVector, pointFeaturesVector)

    // Return the distance calculations
    distFeatures + distSpace
  }

  /**
    * The main private function that does the SLIC algorithm
    * @return 3D INDArray of integers indicating segment labels
    */
  private def calcClusterAssignments (): INDArray = {

    // TODO: Do stuff here

    // Return the clusterAssignments matrix. Cluster Assignments is a matrix of same
    // dimensions and size of the original provided matrix. But the scalar values
    // represent a label of each cluster the voxel point was associated with.
    clusterAssignments
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
    * Defines how to calculate the distance between two datapoints. Since we are working with just
    * one floating point scalar here, this is simply the absolute value of the difference between
    * the two floats. If for instance, we were dealing with a (int, int, int) or (float, float, float)
    * as the value at each voxel point then we would need a much more involved distance func here.
    * For example, if we had (float,float,float) representing RGB values, the following would be
    * sqrt(pow(r1-r2,2) + pow(g1-g2,2) + pow(b1-b2,2))
    * @param x First INDArray
    * @param y Second INDArray
    * @return Float value of distance between the two indarrays
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

    checkBounds(point, (0, 0, 0), dims)
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

    // x -> tuple._1
    // y -> tuple._2
    // z -> tuple._3

    // Remember last statement in defs for Scala return automatically
    point._1 >= minDims._1 & point._1 < maxDims._1 &
      point._2 >= minDims._2 & point._2 < maxDims._2 &
      point._3 >= minDims._3 & point._3 < maxDims._3
  }

  /**
    * Checks Dimensions of the given matrix
    * @return True if matrix dimensions equal 3, false for anything else
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
    if (matrix.shape().apply(0) == xDimSize &&
        matrix.shape().apply(1) == yDimSize &&
        matrix.shape().apply(2) == zDimSize) {
      true
    } else {
      log.warning("Matrix shape does not match provided" +
        "Matrix has Shape:[{}], while provided is {}",
        matrix.shape().mkString(","),
        "["+xDimSize+","+yDimSize+","+zDimSize+","+sDimSize+"]")
      false
    }
  }
}
