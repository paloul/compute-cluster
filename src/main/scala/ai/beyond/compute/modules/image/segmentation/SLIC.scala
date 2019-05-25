package ai.beyond.compute.modules.image.segmentation

import org.nd4j.linalg.api.buffer.DataType
import org.nd4j.linalg.api.ndarray._
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.indexing.conditions._
import org.nd4j.linalg.indexing.{BooleanIndexing, NDArrayIndex}

import scala.collection.parallel.mutable.ParArray


class SLIC (
       matrix: INDArray,
       dimensions: (Int, Int, Int, Int),
       superPixelSize: Int = 20,
       compactness: Float = 1f,
       maxIteration: Int = 2,
       minSuperSize: Int = 1,
       centerDelta: (Float, Float, Float) = (2f, 2f, 2f)
    ) (implicit logger: akka.event.LoggingAdapter) {

  // Cache the logger provided implicitly
  private val log = logger

  // Extract the x.y.z from dimensions for easier accessibility/readability
  private val xDimSize: Int = dimensions._1
  private val yDimSize: Int = dimensions._2
  private val zDimSize: Int = dimensions._3
  private val sDimSize: Int = dimensions._4 // Num properties stored in 4th dimension

  // Defined in SLIC implementation paper
  private val ivtwt = 1.0f / ((superPixelSize / compactness) * ( superPixelSize / compactness))

  // Starting Super Center coordinates. Initialized in main segments function
  private val centerCoords: Array[(Int, Int, Int)] =
    initSuperCenters((xDimSize, yDimSize, zDimSize), superPixelSize)

  // Create a new matrix to hold distance values of each point to a center
  private val storedDistanceMatrix: INDArray = Nd4j.valueArrayOf(
    Array(xDimSize, yDimSize, zDimSize), Float.MaxValue)

  // Create a new matrix to hold the actual cluster labels
  private val clusters: INDArray = Nd4j.valueArrayOf(
    Array(xDimSize.toLong, yDimSize.toLong, zDimSize.toLong), -1, DataType.INT)

  /**
    * Main entry point to get segments of matrix. The only public function on this class.
    * @return An ND4J INDArray representing the world with cluster labels at each voxel point
    */
  def segments (): INDArray = {

    log.info("Starting segmentation process...")

    // Introduce any checks here, add the functions calls to the list
    // Each function call will be evaluated and results stored in checks list
    val checks = List[Boolean](
      checkDataMatrixDimensions(matrix.shape()),
      checkClustersMatrixDimensions(clusters.shape()),
      checkDimensionSizes(matrix.shape()),
      verifyDataAndClustersMatrixSizes(matrix.shape(), clusters.shape())
    )

    // Loop through the checks list and make sure everything passed
    // If checks passed then execute the core _getSegments func
    // list.forall(identity) will fail on first instance of FALSE
    if (checks.forall(identity)) {

      try {

        // Calculate clusters and return the same INDArray that was given to us
        // to store the cluster labels
        calculateClusters()

        // Center Assignments are not directly forced to form a continuous superpixel.
        // The superpixels are made continuous by this function which reassigned
        // isolated "islands" to the best adjacent superpixel.
        enforceConnectivity()

        clusters // Return the cluster labels matrix

      } catch {

        case e: Exception =>
          log.error(e.getMessage)
          // Cleanup the original clusters matrix that was given to us
          //clusters.close()
          // Return an empty nd4j INDArray to mark failure
          Nd4j.empty()
      }

    } else {
      // If any checks failed then reply back with empty INDArray
      log.error("SLIC Parameter Checks failed. Stopping and cleaning up.")
      // Cleanup the original clusters matrix that was given to us
      //clusters.close()
      // Return an empty nd4j INDArray to mark failure
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

    out.toArray
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
            surroundingFeatureVectors.tensorsAlongDimension(3).toInt

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
    * The main private function that does the SLIC algorithm
    * @return 3D INDArray of integers indicating segment labels
    */
  private def calculateClusters (): Unit = {
    import scala.math.floor
    import org.nd4j.linalg.ops.transforms.Transforms.pow
    import org.nd4j.linalg.ops.transforms.Transforms.sqrt
    import org.nd4j.linalg.factory.Broadcast

    log.info("Calculating Clusters...")

    var rounds: Int = 0
    var anyChange: Boolean = false

    // How far out to get voxels surrounding a center in each direction
    val cDeltaX = centerDelta._1
    val cDeltaY = centerDelta._2
    val cDeltaZ = centerDelta._3

    while (!anyChange && rounds < maxIteration) {

      anyChange = false // reset the any change bit

      // Use the indices of the centers to retrieve them,
      // since we use the center index as the cluster label index.
      // Basically this goes through and assigns voxels to clusters
      // based on distance calculations according to SLIC
      val t0_clusterAssign = System.nanoTime()
      for (ci <- (0 until centerCoords.length).toParArray) {

        // Get the current center
        val c = centerCoords(ci)

        // Setup the starting and ending positions for x.y.z.
        // Find points around the center outward of radius superpixelsize*2
        val xStart: Int = if (c._1 - cDeltaX * superPixelSize < 0) 0 else floor(c._1 - cDeltaX * superPixelSize).toInt
        val yStart: Int = if (c._2 - cDeltaY * superPixelSize < 0) 0 else floor(c._2 - cDeltaY * superPixelSize).toInt
        val zStart: Int = if (c._3 - cDeltaZ * superPixelSize < 0) 0 else floor(c._3 - cDeltaZ * superPixelSize).toInt
        val xEnd: Int = if (c._1 + cDeltaX * superPixelSize > xDimSize) xDimSize else floor(c._1 + cDeltaX * superPixelSize).toInt
        val yEnd: Int = if (c._2 + cDeltaY * superPixelSize > yDimSize) yDimSize else floor(c._2 + cDeltaY * superPixelSize).toInt
        val zEnd: Int = if (c._3 + cDeltaZ * superPixelSize > zDimSize) zDimSize else floor(c._3 + cDeltaZ * superPixelSize).toInt

        // Get center voxel information
        val centerVoxelCoords: INDArray = matrix.get(
          NDArrayIndex.point(c._1),
          NDArrayIndex.point(c._2),
          NDArrayIndex.point(c._3),
          NDArrayIndex.interval(0,3) // Indices of coordinates stored in vector
        )
        val centerVoxelFeatures: INDArray = matrix.get(
          NDArrayIndex.point(c._1),
          NDArrayIndex.point(c._2),
          NDArrayIndex.point(c._3),
          NDArrayIndex.interval(3,6) // Indices of actual data features in vector
        )

        val cAroundShape = Array(xEnd - xStart, yEnd - yStart, zEnd - zStart).map(i=>i.toLong)
        val arrayIndexIntervalsX = NDArrayIndex.interval(xStart, xEnd)
        val arrayIndexIntervalsY = NDArrayIndex.interval(yStart, yEnd)
        val arrayIndexIntervalsZ = NDArrayIndex.interval(zStart, zEnd)
        val cAroundClusterLabels = Nd4j.valueArrayOf(cAroundShape, ci)

        // get information on all the voxels surrounding this center point
        val voxelCoords: INDArray = matrix.get(
          arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ,
          NDArrayIndex.interval(0,3) // Indices of coordinates stored in vector
        )
        val voxelFeatures: INDArray = matrix.get(
          arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ,
          NDArrayIndex.interval(3,6) // Indices of actual data features in vector
        )

        // broadcast subtraction of center point to range of all voxels
        // we want to get coord distance of. this is because shapes are not the same
        // Voxel Coords is a 3D shape surrounding the center point. Center Coords is
        // a scalar vector since we pull out 4th dimension using ArrayIndex.point.
        val resultForSubCoords: INDArray = Nd4j.valueArrayOf(voxelCoords.shape(), -1f)
        val subCoords = Broadcast.sub(
          voxelCoords, centerVoxelCoords, resultForSubCoords, 3)
        //log.info("Sub Coords: \n{}", subCoords)

        // broadcast subtraction of center point to range of all voxels
        // we want to get feature distance of. this is because shapes are not the same.
        // Voxel Features is a 3D shape surrounding the center point. Center Features is
        // a scalar vector since we pull out 4th dimension using ArrayIndex.point.
        val resultForSubFeatures: INDArray = Nd4j.valueArrayOf(voxelFeatures.shape(), -1f)
        val subFeatures = Broadcast.sub(
          voxelFeatures, centerVoxelFeatures, resultForSubFeatures, 3)
        //log.info("Sub Features: \n{}", subFeatures)

        // Calculate the distances of coordinates and features. This will give us a matrix
        // of shape (xEnd-xStart, yEnd-yStart, zEnd-zStart)
        // calculate the distance of coords with center. multiply by ivtwt is a SLIC implementation
        val distanceCoord = sqrt(pow(subCoords, 2).sum(3)).muli(ivtwt)
        //log.info("Distance Coords: \n{}", distanceCoord)
        // calculate the distance of coords with center
        val distanceFeatures = sqrt(pow(subFeatures, 2).sum(3))
        //log.info("Distance Features: \n{}", distanceFeatures)

        // Add the distance measurements for coordinate space and feature together for each voxel
        val calculatedDistances = distanceCoord.addi(distanceFeatures) // addi is in place addition
        //log.info("Calculated Distances: \n{}", calculatedDistances)

        // Get the current slice of voxels from storedDistanceMatrix which holds distances
        // for the complete larger matrix provided at creation
        val storedDistances = storedDistanceMatrix.get(
          arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ
        )

        // Get a view into the cluster matrix where we store cluster labels.
        // Getting a view will focus the area and give us a subset of the larger matrix
        // of the current voxels that we are computing over
        val clusterAssignment = clusters.get(
          arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ
        )

        // The distances stored in calculatedDistances should be smaller than
        // the current cluster center stored previously in storedDistances for another Center.
        // The lengths of calculatedDistances, storedDistances, and clusterAssignment
        // are of the same length, as we get views into the larger underlying matrix

        // Generate a mask of all voxel points where calculated distance is less that stored Distance.
        // lt = Less Than -> gives back a INDArray with BOOL types
        val isCloserMask = calculatedDistances.lt(storedDistances).castTo(DataType.INT)
        val replaceMaskHelper = Nd4j.valueArrayOf(cAroundShape, -1, DataType.INT)
        // Inverse the bits on the mask, since the putwherewithmask works on 0's not 1's
        BooleanIndexing.replaceWhere(isCloserMask, replaceMaskHelper, Conditions.equals(0))
        replaceMaskHelper.assign(0)
        BooleanIndexing.replaceWhere(isCloserMask, replaceMaskHelper, Conditions.equals(1))
        replaceMaskHelper.assign(1)
        BooleanIndexing.replaceWhere(isCloserMask, replaceMaskHelper, Conditions.equals(-1))
        //log.info("Is Closer Mask:\n{}", isCloserMask)

        //log.info("Stored Distances: \n{}", storedDistances)
        //log.info("Calculated Distances: \n{}", calculatedDistances)

        // Put calculated values that were less than stored with the boolean mask. PutWhereWithMask
        // generates a dupe so the underlying matrix is not modified. Which is why we must do Assign.
        val replacedStoredDistances = storedDistances.putWhereWithMask(isCloserMask, calculatedDistances)
        storedDistances.assign(replacedStoredDistances) // Assign values from putwherewithmask
        //log.info("Stored Distances After Assign: \n{}", storedDistances)

        // Using the mask now set the new Cluster/Center Index for all
        // the points that were less than the stored Distance

        val replacedClusterAssignment = clusterAssignment.putWhereWithMask(
          isCloserMask,
          cAroundClusterLabels) // Create array here as API expects it.
        //log.info("Replaced Cluster Assignments: \n{}", replacedClusterAssignment)

        // Assign values from putwherewithmask
        clusterAssignment.assign(replacedClusterAssignment)
        //log.info("Cluster ID: {} \n{}", ci, clusterAssignment)

      }

      // Increment the round counter
      rounds += 1

      val t1_clusterAssign = System.nanoTime()
      log.info("Round [{}] cluster assignment time [{} (s)]",
        rounds,
        (t1_clusterAssign - t0_clusterAssign) / 1E9)
    }
  }

  /**
    * Center Assignments are not directly forced to form a continuous superpixel.
    * The superpixels are made continuous by this function which reassigned
    * isolated "islands" to the best adjacent superpixel.
    */
  private def enforceConnectivity(): Unit = {

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
    * Checks Dimensions of the given data matrix
    * @return True if matrix dimensions equal 4, false for anything else
    */
  private def checkDataMatrixDimensions(matrix: Array[Long]): Boolean = {
    if (matrix.length == 4) {
      true
    } else {
      log.warning("SLIC expects data Matrix with FOUR dimensions. " +
        "Given data Matrix only has [{}]", matrix.length)
      false
    }
  }

  /**
    * Checks Dimensions of the given clusters label matrix
    * @return True if matrix dimensions equal 3, false for anything else
    */
  private def checkClustersMatrixDimensions(clusters: Array[Long]): Boolean = {
    if (clusters.length == 3) {
      true
    } else {
      log.warning("SLIC expects cluster labels Matrix with THREE dimensions. " +
        "Given cluster labels Matrix only has [{}]", clusters.length)
      false
    }
  }

  /**
    * Checks the actual size of the matrix against the dimensions provided by client
    * @return
    */
  private def checkDimensionSizes(matrix: Array[Long]): Boolean = {
    if ((xDimSize > 0 && matrix.apply(0) == xDimSize) &&
        (yDimSize > 0 && matrix.apply(1) == yDimSize) &&
        (zDimSize > 0 && matrix.apply(2) == zDimSize)) {
      true
    } else {
      log.warning("Matrix shape does not match provided" +
        "Matrix has Shape:[{}], while provided is {}",
        matrix.mkString(","),
        "["+xDimSize+","+yDimSize+","+zDimSize+","+sDimSize+"]")
      false
    }
  }

  /**
    * Compares the sizes of the value matrix and the clusters matrix provided as they
    * need to match in dimensions and sizes
    * @param matrix 4-dimensional NDArray holding feature values
    * @param clusters 4-dimensional INDArray holding cluster labels and distance
    * @return
    */
  private def verifyDataAndClustersMatrixSizes(
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
