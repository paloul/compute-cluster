package ai.beyond.compute.modules.image.segmentation


import org.nd4j.linalg.api.ndarray._
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.indexing.NDArrayIndex


class SLIC (
             matrix: INDArray,
             dimensions: (Int, Int, Int),
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

  private val dx: INDArray = Nd4j.create(Array(-1f, 1f, 0f, 0f, 0f, 0f))
  private val dy: INDArray = Nd4j.create(Array(0f, 0f, -1f, 1f, 0f, 0f))
  private val dz: INDArray = Nd4j.create(Array(0f, 0f, 0f, 0f, -1f, 1f))

  // Validate super pixel size against each axis size
  private val xS = if (xDimSize < superPixelSize) xDimSize else superPixelSize
  private val yS = if (yDimSize < superPixelSize) yDimSize else superPixelSize
  private val zS = if (zDimSize < superPixelSize) zDimSize else superPixelSize

  // Setup min super size, check if -1 provided
  private val minSuperSize = if (_minSuperSize >= 0) _minSuperSize else (xS * yS * zS) / 2

  // Setup compactness, check if Min Value passed
  private val compactness: Float =
    if (superPixelSize == Float.MinValue) superPixelSize else _compactness

  // Create a new ND4j matrix of 3 dimensions the same shape as matrix, fill
  // it with -5 values as initial cluster values, i.e. -5 means no cluster assigned
  private val clusterAssignments: INDArray =
    Nd4j.valueArrayOf(Array(xDimSize, yDimSize, zDimSize), -5f)


  /**
    * Main entry point to get segments of matrix. The only public function on this class.
    * @return An ND4J INDArray representing the world with cluster labels at each voxel point
    */
  def segments (): INDArray = {

    log.info("Starting SLIC...")

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

      // TODO: Do stuff here
      calculate()

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
                               gridInterval: Int): Array[(Int, Int, Int)] = {
    import scala.math._

    // x -> tuple._1
    // y -> tuple._2
    // z -> tuple._3

    val xStart: Int = if (dims._1 <= gridInterval) floor(dims._1 / 2).toInt else round(gridInterval / 2f)
    val yStart: Int = if (dims._2 <= gridInterval) floor(dims._2 / 2).toInt else round(gridInterval / 2f)
    val zStart: Int = if (dims._3 <= gridInterval) floor(dims._3 / 2).toInt else round(gridInterval / 2f)

    val out = for {
      x_s <- xStart until dims._1 by gridInterval;
      y_s <- yStart until dims._2 by gridInterval;
      z_s <- zStart until dims._3 by gridInterval;
    } yield {
      (x_s, y_s, z_s)
    }

    out.toArray
  }

  /**
    * Super pixel centers need to avoid initialization on a clear edge. Perturb the centers
    * to the lowest 'discrete' gradient in a small radius/cube around the original init.
    * @param centers
    * @param adjustBy
    * @return Modified list of Centers as [(int, int, int)]
    */
  private def adjustSuperCentersToLowContrast(
                               centers: Array[(Int, Int, Int)],
                               adjustBy: Int = 3): Array[(Int, Int, Int)] = {

    log.info("Adjusting Super Centers to Low Contrast...")
    centers.map(c => {
      val xC: Int = c._1
      val yC: Int = c._2
      val zC: Int = c._3

      var maxScore: Float = 0.0f
      var bestMove: (Int, Int, Int) = (0, 0, 0)

      // Pull out a slice of the main array around the current center point, this is
      // returned as a view of the original matrix. Modifications to values effects original
      val matrixAroundCenterPoint: INDArray = matrix.get(
        NDArrayIndex.interval(xC - adjustBy, xC + adjustBy),
        NDArrayIndex.interval(yC - adjustBy, yC + adjustBy),
        NDArrayIndex.interval(zC - adjustBy, zC + adjustBy)
      )

      // Get all other points surrounding each center -adjustBy to adjustBy out
      for {

        dx <- (-adjustBy) until adjustBy;
        dy <- (-adjustBy) until adjustBy;
        dz <- (-adjustBy) until adjustBy;

      } {

        // Retrieve scalar value from matrix given points around the center and determine
        // if its the best move to make.
        // TODO: This can be improved as retrieve of scalar values from matrix via getFloat()
        //  and indices is slow. If we can retrieve parts of the matrix and execute on it,
        //  computation should be faster
        if (checkBounds((xC + dx, yC + dy, zC + dz), (xDimSize, yDimSize, zDimSize))) {
          var difSum: Float = 0.0f
          val currValue: Float = matrix.getFloat(Array(xC + dx, yC + dy, zC + dz))

          if (dx + xC + 1 < xDimSize)
            difSum += distanceFunction(currValue, matrix.getFloat(Array(dx + xC + 1, dy + yC, dz + zC)))
          if (dx + xC - 1 >= 0)
            difSum += distanceFunction(currValue, matrix.getFloat(Array(dx + xC - 1, dy + yC, dz + zC)))
          if (dy + yC + 1 < yDimSize)
            difSum += distanceFunction(currValue, matrix.getFloat(Array(dx + xC, dy + yC + 1, dz + zC)))
          if (dy + yC - 1 >= 0)
            difSum += distanceFunction(currValue, matrix.getFloat(Array(dx + xC, dy + yC - 1, dz + zC)))
          if (dz + zC + 1 < zDimSize)
            difSum += distanceFunction(currValue, matrix.getFloat(Array(dx + xC, dy + yC, dz + zC + 1)))
          if (dz + zC - 1 >= 0)
            difSum += distanceFunction(currValue, matrix.getFloat(Array(dx + xC, dy + yC, dz + zC - 1)))

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
    * The main private function that does the SLIC algorithm
    * @return 3D INDArray of integers indicating segment labels
    */
  private def calculate (): INDArray = {

    // Return the clusterAssignments matrix. Cluster Assignments is a matrix of same
    // dimensions and size of the original provided matrix. But the scalar values
    // represent a label of each cluster the voxel point was associated with.
    clusterAssignments
  }

  /**
    * Defines how to calculate the distance between two datapoints. Since we are working with just
    * one floating point scalar here, this is simply the absolute value of the difference between
    * the two floats. If for instance, we were dealing with a (int, int, int) or (float, float, float)
    * as the value at each voxel point then we would need a much more involved distance func here.
    * For example, if we had (float,float,float) representing RGB values, the following would be
    * sqrt(pow(r1-r2,2) + pow(g1-g2,2) + pow(b1-b2,2))
    * @param a First value
    * @param b Second value
    * @return
    */
  private def distanceFunction(a: Float, b: Float): Float = {
    import scala.math.abs

    abs(a - b)
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
    if (matrix.shape().length == 3) {
      true
    } else {
      log.warning("SLIC expects Matrix with 3 dimensions. " +
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
        "Matrix has Shape:[{}], while provided is [{},{},{}]",
        matrix.shape().toString(), xDimSize, yDimSize, zDimSize)
      false
    }
  }
}
