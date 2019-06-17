package ai.beyond.compute.modules.image.segmentation

import org.nd4j.linalg.api.buffer.DataType
import org.nd4j.linalg.api.ndarray._
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.indexing.conditions._
import org.nd4j.linalg.indexing.{BooleanIndexing, NDArrayIndex}
import org.nd4j.linalg.api.ops.impl.reduce.longer.MatchCondition
import org.nd4j.linalg.ops.transforms.Transforms

import scala.collection.parallel.immutable.ParRange

class SLIC (
       matrix: INDArray,
       dimensions: (Int, Int, Int, Int),
       superPixelSize: Int = 15,
       compactness: Float = .01f,
       maxIteration: Int = 10,
       minChangePerRound: Double = 0.5,
       minSuperSize: Int = Int.MinValue,
       centerDelta: (Float, Float, Float) = (2.5f, 2.5f, 2.5f)
    ) (implicit logger: akka.event.LoggingAdapter) {

  // Simple Case class that defines a center. Holds more than just x.y.z values as we need
  // to calculate averages for centers and move x.y.z defining this center according to weight
  // of voxels assigned to this center
  private case class Center(
                 var x: Int,
                 var y: Int,
                 var z: Int,
                 var nVoxels: Int,
                 var voxelFeatureAvgs: INDArray)

  // Cache the logger provided implicitly
  private val log = logger

  // Extract the x.y.z from dimensions for easier accessibility/readability
  private val xDimSize: Int = dimensions._1
  private val yDimSize: Int = dimensions._2
  private val zDimSize: Int = dimensions._3
  private val sDimSize: Int = dimensions._4 // Num properties stored in 4th dimension

  // Defined in SLIC implementation paper
  private val ivtwt = 1.0f / ((superPixelSize / compactness) * (superPixelSize / compactness))

  // Starting Super Center coordinates. Initialized in main segments function
  private var centerCoords: Array[Center] = _

  // Matrix to hold distance values of each point to a center
  private var storedDistanceMatrix: INDArray = _

  // Matrix to hold the actual cluster labels
  private var clusters: INDArray = _

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
      checkDataMatrixDimensionSizes(matrix.shape())
    )

    // Loop through the checks list and make sure everything passed
    // If checks passed then execute the core _getSegments func
    // list.forall(identity) will fail on first instance of FALSE
    if (checks.forall(identity)) {

      try {

        // Initialize Centers
        centerCoords = initSuperCenters((xDimSize, yDimSize, zDimSize, sDimSize), superPixelSize)

        // Create the temporary matrix to store distance calculations
        storedDistanceMatrix = Nd4j.valueArrayOf(
          Array(xDimSize, yDimSize, zDimSize), Float.MaxValue)

        // Create the matrix to store actual cluster labels
        clusters = Nd4j.valueArrayOf(
          Array(xDimSize.toLong, yDimSize.toLong, zDimSize.toLong), -1, DataType.INT)

        // Calculate clusters and return the same INDArray that was given to us
        // to store the cluster labels
        calculateClusters()

        // Center Assignments are not directly forced to form a continuous superpixel.
        // The superpixels are made continuous by this function which reassigned
        // isolated "islands" to the best adjacent superpixel.
        if (minSuperSize > 0) enforceConnectivity()

        clusters // Return the cluster labels matrix

      } catch {

        case e: Exception =>
          log.error(e.getMessage)
          log.error(e.getStackTrace.mkString("\n"))
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
  private def initSuperCenters(dims: (Int, Int, Int, Int),
                               gridInterval: Int): Array[Center] = {
    import scala.math.floor
    import scala.math.round

    log.info("Initializing Super Centers...")

    val xStart: Int = if (dims._1 <= gridInterval) floor(dims._1 / 2).toInt else round(gridInterval / 2f)
    val yStart: Int = if (dims._2 <= gridInterval) floor(dims._2 / 2).toInt else round(gridInterval / 2f)
    val zStart: Int = if (dims._3 <= gridInterval) floor(dims._3 / 2).toInt else round(gridInterval / 2f)

    val centersGrid = for {
      x_s <- xStart until dims._1 by gridInterval;
      y_s <- yStart until dims._2 by gridInterval;
      z_s <- zStart until dims._3 by gridInterval
    } yield {
      //(x_s, y_s, z_s)
      // Create a new center with coordinates, initiate count to 0, initiate features vector to 0
      // Feature vector size is -3 to take out the the x.y.z values stored in the fourth dim of source
      Center(x_s, y_s, z_s, 0, Nd4j.zeros(dims._4 - 3))
    }

    log.info("Super Centers Initialized [{}]", centersGrid.length)

    // Optimize the centers and get them back as an array
    val optimumCentersGrid = adjustCentersAwayFromEmptyRegions(centersGrid)
    //optimumCentersGrid

    val perturbedCentersGrid = adjustSuperCentersToLowContrast(optimumCentersGrid)
    perturbedCentersGrid // Return optimum placed centers
  }

  /**
    * Super pixel centers need to avoid initialization on NaN values. Perturb the centers
    * to the closest 'discrete' gradient in a radius/cube around the original superpixel init.
    * @param centers A Indexed Sequence (int,int,int) representing grid centers in matrix
    * @return Modified list of Centers as Indexed Sequence [(int, int, int)]
    */
  private def adjustCentersAwayFromEmptyRegions(
                     centers: IndexedSeq[Center]): Array[Center] = {

    log.info("Optimizing Grid Super Centers...")

    // Create an empty marked for removal container for centers that we cannot find any
    // reasonable alternative for. This will help us keep centers down if they are just NaNs
    var markedForRemoval: IndexedSeq[Center] = IndexedSeq()

    // Create an empty marked for addition container to add potential new centers identified
    // that can replace the ones marked for deletion.
    var markedForAddition: IndexedSeq[Center] = IndexedSeq()

    // Get parallel indices to traverse centers
    val centerIndices: ParRange = centers.indices.par

    // Loop through each center
    for (ci <- centerIndices) {

      // Get the current center
      val c = centers(ci)

      // Get center voxel information
      val centerVoxelCoords: INDArray = matrix.get(
        NDArrayIndex.point(c.x),
        NDArrayIndex.point(c.y),
        NDArrayIndex.point(c.z),
        NDArrayIndex.interval(0,3) // Indices of coordinates stored in vector
      )

      // Find out if there are any NaNs in this current center, if so
      // then proceed to see around it and if we can find an alternate center within range
      val opFindNumNaNs = new MatchCondition(centerVoxelCoords, Conditions.isNan)
      val numNaNs = Nd4j.getExecutioner.exec(opFindNumNaNs).getInt(0)
      if (numNaNs > 0) {

        // Mark this center for deletion as its on a NaN voxel with no data recorded.
        markedForRemoval = markedForRemoval :+ c

        // Attempt to find an alternate for the one we just marked for deletion. Its not
        // guaranteed that we will find an alternate. If we do then add it into centers
        val alternate: (Boolean, Center) = findAlternateForCenter(c)

        if (alternate._1) {
          // We did find an alternate for the current center. Add it to our temp collection
          markedForAddition = markedForAddition :+ alternate._2
        }
      }
    }

    log.info("Found {} centers to delete", markedForRemoval.length)
    log.info("Found {} alternate centers as replacements", markedForAddition.length)

    // Get the difference of original centers and marker for removal centers. Add in the
    // alternate centers identified (if any) for those that were deleted.
    val optimumCenters = centers.diff(markedForRemoval) ++ markedForAddition

    log.info("Optimized Grid Super Centers [{}]", optimumCenters.length)

    optimumCenters.toArray // Return optimum centers as array
  }

  /**
    * Finds an alternate voxel point around the given Center coordinate within superpixel size
    * @param c The center to which to find alternates for
    * @return A boolean and center coordinate pair, if false then center coordinates are -1
    */
  private def findAlternateForCenter(c: Center): (Boolean, Center) = {

    import scala.math.floor

    log.debug("Searching alternate for Center({},{},{})...", c.x, c.y, c.z)

    // Setup the starting and ending positions for x.y.z.
    // Find points around the center within starting superpixel size
    val xStart: Int = if (c.x - superPixelSize/2 < 0) 0 else floor(c.x - superPixelSize/2).toInt
    val yStart: Int = if (c.y - superPixelSize/2 < 0) 0 else floor(c.y - superPixelSize/2).toInt
    val zStart: Int = if (c.z - superPixelSize/2 < 0) 0 else floor(c.z - superPixelSize/2).toInt
    val xEnd: Int = if (c.x + superPixelSize/2 > xDimSize) xDimSize else floor(c.x + superPixelSize/2).toInt
    val yEnd: Int = if (c.y + superPixelSize/2 > yDimSize) yDimSize else floor(c.y + superPixelSize/2).toInt
    val zEnd: Int = if (c.z + superPixelSize/2 > zDimSize) zDimSize else floor(c.z + superPixelSize/2).toInt

    val arrayIndexIntervalsX = NDArrayIndex.interval(xStart, xEnd)
    val arrayIndexIntervalsY = NDArrayIndex.interval(yStart, yEnd)
    val arrayIndexIntervalsZ = NDArrayIndex.interval(zStart, zEnd)

    // get information on all the voxels surrounding this center point
    val voxelCoords: INDArray = matrix.get(
      arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ,
      NDArrayIndex.interval(0,3) // Indices of coordinates stored in vector
    )

    // Check to see if the number of NaNs equals num of all voxels within range of this center,
    // if they are equal then that means there is no viable alternate within range. return false
    val opFindNumNaNs = new MatchCondition(voxelCoords, Conditions.isNan)
    val numNaNs = Nd4j.getExecutioner.exec(opFindNumNaNs).getInt(0)
    if (numNaNs == voxelCoords.size(3)) {

      // Num NaNs equal num voxels. No potential candidates.
      (false, null)

    } else {

      // Potential candidates available. Find one.
      var vi = 0
      var foundAlternate: Boolean = false
      // Case Class copy create a new instance of the case class but maintains its
      // references to the original objects stored in the case class, x,y,z,n,IndArray
      val alternate: Center = c.copy()

      val numVoxels = voxelCoords.vectorsAlongDimension(3)
      while(!foundAlternate && vi < numVoxels) {
        val vector = voxelCoords.vectorAlongDimension(vi, 3)
        if (!vector.getFloat(0).isNaN) { // Just check for the x value. The rest will be NaN too
          foundAlternate = true

          // Change the alternate's x.y.z
          alternate.x = vector.getFloat(0).toInt
          alternate.y = vector.getFloat(1).toInt
          alternate.z = vector.getFloat(2).toInt

          log.debug("Found alternate Center ({},{},{})",
            alternate.x, alternate.y, alternate.z)
        }

        vi = vi + 1 // increment counter
      }

      (foundAlternate, alternate)

    }

  }

  /**
    * Super pixel centers need to avoid initialization on a clear edge. Perturb the centers
    * to the lowest 'discrete' gradient in a small radius/cube around the original init.
    * @param centers An Array of (int,int,int) representing grid centers in matrix
    * @param adjustBy The buffer of the sphere around center point to search for low contrast
    * @return Modified list of Centers as [(int, int, int)]
    */
  private def adjustSuperCentersToLowContrast(
                         centers: Array[Center],
                         adjustBy: Int = 3): Array[Center] = {

    import scala.math.floor
    import org.nd4j.linalg.ops.transforms.Transforms.pow
    import org.nd4j.linalg.ops.transforms.Transforms.sqrt
    import org.nd4j.linalg.factory.Broadcast

    log.info("Perturbing Super Centers to Low Contrast...")

    // Loop through each current center
    for (ci <- centers.indices.par) {

      val c = centers(ci)

      // Scores and best move for current center
      var maxScore: Float = 0f // Help to determine what the max score is for a particular center

      // Calc starting and ending positions for x.y.z. around current center with adjustBy
      // This will define candidate new centers around this existing current center
      val xStart: Int = if (c.x - adjustBy < 0) 0 else floor(c.x - adjustBy).toInt
      val yStart: Int = if (c.y - adjustBy < 0) 0 else floor(c.y - adjustBy).toInt
      val zStart: Int = if (c.z - adjustBy < 0) 0 else floor(c.z - adjustBy).toInt
      val xEnd: Int = if (c.x + adjustBy > xDimSize) xDimSize else floor(c.x + adjustBy).toInt
      val yEnd: Int = if (c.y + adjustBy > yDimSize) yDimSize else floor(c.y + adjustBy).toInt
      val zEnd: Int = if (c.z + adjustBy > zDimSize) zDimSize else floor(c.z + adjustBy).toInt

      // Generate points for all potentially new centers around existing center
      val potentialNewCenters = for {
        x_s <- xStart until xEnd
        y_s <- yStart until yEnd
        z_s <- zStart until zEnd
      } yield {
        (x_s, y_s, z_s)
      }

      // Loop through and calculate values for each new center
      // Determine which center is best
      potentialNewCenters.foreach(newCenter => {

        val newCenterVoxelCoords: INDArray = matrix.get(
          NDArrayIndex.point(newCenter._1),
          NDArrayIndex.point(newCenter._2),
          NDArrayIndex.point(newCenter._3),
          NDArrayIndex.interval(0,3) // Indices of coordinates stored in vector
        )
        val newCenterVoxelFeatures: INDArray = matrix.get(
          NDArrayIndex.point(newCenter._1),
          NDArrayIndex.point(newCenter._2),
          NDArrayIndex.point(newCenter._3),
          NDArrayIndex.interval(3,sDimSize) // Indices of actual data features in vector
        )

        // Calculate starting and end indices for voxels surrounding the candidate
        val ncxStart: Int = if (newCenter._1 - 1 < 0) 0 else floor(newCenter._1 - 1).toInt
        val ncyStart: Int = if (newCenter._2 - 1 < 0) 0 else floor(newCenter._2 - 1).toInt
        val nczStart: Int = if (newCenter._3 - 1 < 0) 0 else floor(newCenter._3 - 1).toInt
        val ncxEnd: Int = if (newCenter._1 + 1 > xDimSize) xDimSize else floor(newCenter._1 + 1).toInt
        val ncyEnd: Int = if (newCenter._2 + 1 > yDimSize) yDimSize else floor(newCenter._2 + 1).toInt
        val nczEnd: Int = if (newCenter._3 + 1 > zDimSize) zDimSize else floor(newCenter._3 + 1).toInt

        val arrayIndexIntervalsX = NDArrayIndex.interval(ncxStart, ncxEnd)
        val arrayIndexIntervalsY = NDArrayIndex.interval(ncyStart, ncyEnd)
        val arrayIndexIntervalsZ = NDArrayIndex.interval(nczStart, nczEnd)

        // get information on all the voxels surrounding this center point
        val voxelCoords: INDArray = matrix.get(
          arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ,
          NDArrayIndex.interval(0,3) // Indices of coordinates stored in vector
        )
        val voxelFeatures: INDArray = matrix.get(
          arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ,
          NDArrayIndex.interval(3,sDimSize) // Indices of actual data features in vector
        )

        // broadcast subtraction of center point to range of all voxels
        // we want to get coord distance of. this is because shapes are not the same
        // Voxel Coords is a 3D shape surrounding the center point. Center Coords is
        // a scalar vector since we pull out 4th dimension using ArrayIndex.point.
        val resultForSubCoords: INDArray = Nd4j.valueArrayOf(voxelCoords.shape(), -1f)
        val subCoords = Broadcast.sub(
          voxelCoords, newCenterVoxelCoords, resultForSubCoords, 3)

        // broadcast subtraction of center point to range of all voxels
        // we want to get feature distance of. this is because shapes are not the same.
        // Voxel Features is a 3D shape surrounding the center point. Center Features is
        // a scalar vector since we pull out 4th dimension using ArrayIndex.point.
        val resultForSubFeatures: INDArray = Nd4j.valueArrayOf(voxelFeatures.shape(), -1f)
        val subFeatures = Broadcast.sub(
          voxelFeatures, newCenterVoxelFeatures, resultForSubFeatures, 3)

        // Calculate the distances of coordinates and features. This will give us a matrix
        // of shape (xEnd-xStart, yEnd-yStart, zEnd-zStart)
        // calculate the distance of coords with center. multiply by ivtwt is a SLIC implementation
        val distanceCoord = sqrt(pow(subCoords, 2).sum(3)).muli(ivtwt)
        // calculate the distance of coords with center
        val distanceFeatures = sqrt(pow(subFeatures, 2).sum(3))
        //log.info("Distance Features: \n{}", distanceFeatures)

        // Add the distance measurements for coordinate space and feature together for each voxel
        val calculatedDistances = distanceCoord.addi(distanceFeatures) // addi is in place addition

        // Replace potential NaNs before proceeding
        BooleanIndexing.replaceWhere(calculatedDistances,0f, Conditions.isNan)

        val distSum = calculatedDistances.sumNumber().floatValue()

        // If difSum is less than current Max Score then we found a good new
        // center to replace the current center. Save new center values and update Max Score
        if (distSum > maxScore) {
          c.x = newCenter._1
          c.y = newCenter._2
          c.z = newCenter._3

          maxScore = distSum
        }
      })

    }

    centers
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
    var lastChange: Double = Double.MaxValue

    // How far out to get voxels surrounding a center in each direction
    val cDeltaX = centerDelta._1
    val cDeltaY = centerDelta._2
    val cDeltaZ = centerDelta._3

    // Parallel Range for center coordinate indices
    val centerCoordsIndices: ParRange = centerCoords.indices.par

    //while (anyChange && rounds < maxIteration) {
    while (rounds < maxIteration & lastChange > minChangePerRound) {

      // Increment the round counter
      rounds += 1

      log.info("Starting Round [{}]...", rounds)
      val t0_round = System.nanoTime()

      // Use the indices of the centers to retrieve them,
      // since we use the center index as the cluster label index.
      // Basically this goes through and assigns voxels to clusters
      // based on distance calculations according to SLIC
      val t0_clusterAssign = System.nanoTime()
      for (ci <- centerCoordsIndices) {

        // Get the current center
        val c = centerCoords(ci)

        // Setup the starting and ending positions for x.y.z.
        // Find points around the center outward of radius superpixelsize*2
        val xStart: Int = if (c.x - cDeltaX * superPixelSize < 0) 0 else floor(c.x - cDeltaX * superPixelSize).toInt
        val yStart: Int = if (c.y - cDeltaY * superPixelSize < 0) 0 else floor(c.y - cDeltaY * superPixelSize).toInt
        val zStart: Int = if (c.z - cDeltaZ * superPixelSize < 0) 0 else floor(c.z - cDeltaZ * superPixelSize).toInt
        val xEnd: Int = if (c.x + cDeltaX * superPixelSize > xDimSize) xDimSize else floor(c.x + cDeltaX * superPixelSize).toInt
        val yEnd: Int = if (c.y + cDeltaY * superPixelSize > yDimSize) yDimSize else floor(c.y + cDeltaY * superPixelSize).toInt
        val zEnd: Int = if (c.z + cDeltaZ * superPixelSize > zDimSize) zDimSize else floor(c.z + cDeltaZ * superPixelSize).toInt

        // Create the center voxel coords from the center coords stored in current Center class object
        val centerVoxelCoords: INDArray = Nd4j.create(Array(c.x,c.y,c.z), Array(3l), DataType.FLOAT)

        // If no voxel has been added to this center yet then assign starting
        // features of current center from main matrix to it. Means this was the first run
        if (c.nVoxels == 0) {
          val centerVoxelFeatures: INDArray = matrix.get(
            NDArrayIndex.point(c.x),
            NDArrayIndex.point(c.y),
            NDArrayIndex.point(c.z),
            NDArrayIndex.interval(3,sDimSize) // Indices of actual data features in vector
          )

          c.voxelFeatureAvgs.addi(centerVoxelFeatures)
        }

        val cAroundShape = Array(xEnd - xStart, yEnd - yStart, zEnd - zStart)
        val arrayIndexIntervalsX = NDArrayIndex.interval(xStart, xEnd)
        val arrayIndexIntervalsY = NDArrayIndex.interval(yStart, yEnd)
        val arrayIndexIntervalsZ = NDArrayIndex.interval(zStart, zEnd)
        val cAroundClusterLabels = Nd4j.createUninitialized(cAroundShape).assign(ci)

        // get information on all the voxels surrounding this center point
        val voxelCoords: INDArray = matrix.get(
          arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ,
          NDArrayIndex.interval(0,3) // Indices of coordinates stored in vector
        )
        val voxelFeatures: INDArray = matrix.get(
          arrayIndexIntervalsX, arrayIndexIntervalsY, arrayIndexIntervalsZ,
          NDArrayIndex.interval(3,sDimSize) // Indices of actual data features in vector
        )

        // broadcast subtraction of center point to range of all voxels
        // we want to get coord distance of. this is because shapes are not the same
        // Voxel Coords is a 3D shape surrounding the center point. Center Coords is
        // a scalar vector since we pull out 4th dimension using ArrayIndex.point.
        val resultForSubCoords: INDArray = Nd4j.createUninitialized(voxelCoords.shape())
        val subCoords = Broadcast.sub(
          voxelCoords, centerVoxelCoords, resultForSubCoords, 3)

        // broadcast subtraction of center point to range of all voxels
        // we want to get feature distance of. this is because shapes are not the same.
        // Voxel Features is a 3D shape surrounding the center point. C.voxelFeaturesAvgs
        // is a vector with three columns
        val resultForSubFeatures: INDArray = Nd4j.createUninitialized(voxelFeatures.shape())
        val subFeatures = Broadcast.sub(
          voxelFeatures, c.voxelFeatureAvgs, resultForSubFeatures, 3)

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

        // Generate a result of all voxel points where calculated distance is less that stored Distance.
        // lt = Less Than -> gives back a INDArray with BOOL types
        val isCloser = calculatedDistances.lt(storedDistances)

        // Check if any voxel changed cluster assignment, just cast bools to int,
        // and then sum the whole array to get number of voxels that changed
        val numVoxelsChanged = isCloser.castTo(DataType.INT).sumNumber().intValue()

        log.debug("[{}]Changed [{}]Total for Center[{}]", numVoxelsChanged, voxelCoords.length(), ci)

        // If we have any voxels that are changing labels then change them and update their stored distance
        if (numVoxelsChanged > 0) {

          // Take the NOT of the result to turn it into a mask,
          // meaning 0 allows pass through where 1 does not
          val isCloserMask = Transforms.not(isCloser)

          // Put calculated values that were less than stored with the boolean mask. PutWhereWithMask
          // generates a dupe so the underlying matrix is not modified. Which is why we must do Assign.
          val replacedStoredDistances = storedDistances.putWhereWithMask(isCloserMask, calculatedDistances)
          storedDistances.assign(replacedStoredDistances) // Assign values from putwherewithmask

          // Using the mask now set the new Cluster/Center Index for all
          // the points that were less than the stored Distance
          val replacedClusterAssignment = clusterAssignment.putWhereWithMask(
            isCloserMask,
            cAroundClusterLabels) // Create array here as API expects it.
          clusterAssignment.assign(replacedClusterAssignment) // Assign values from putwherewithmask
        }
      }
      val t1_clusterAssign = System.nanoTime()
      log.info("Round [{}] cluster assignments [{} (s)]",
        rounds,
        (t1_clusterAssign - t0_clusterAssign) / 1E9)

      // NOTE: At this point adjust the centers to reflect assignments i.e. averaging
      //  features and moving center positions to reflect new center of mass of the cluster

      var totalCentersChange: Double = 0
      val t0_calculateCenterAverages = System.nanoTime()
      for (ci <- centerCoordsIndices) {

        val center = centerCoords(ci)

        // Find all matrix positions that are assigned to current center index: ci
        val ciAssignments = clusters.eq(ci)

        // Get total num of voxel assigned to this center. Since this is a bool matrix
        // with 1's and 0's after being casted to Int, just add all values in matrix to
        // get the total assigned
        center.nVoxels = ciAssignments.castTo(DataType.INT).sumNumber().intValue()
        log.debug("[{}] Voxels Assigned to Center[{}]", center.nVoxels, ci)

        // Get three 3-dim matrices each representing x.y.z respectively
        val matrixCoordsX = matrix.get(
          NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.point(0)
        )
        val matrixCoordsY = matrix.get(
          NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.point(1)
        )
        val matrixCoordsZ = matrix.get(
          NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.point(2)
        )

        // Float casted ci assignments for multiplication
        val ciAssignmentsFloat = ciAssignments.castTo(DataType.FLOAT)

        // Create a temp coords matrix in the shape of the above three. This
        // is to avoid having to create a large matrix over and over.
        // Assign values from each respective matrix then do multiple inplace
        val tempMatrix = Nd4j.create(xDimSize, yDimSize, zDimSize, 1)

        // Multiply each coordinate axis values with the ciAssignments bool matrix
        // casted to an Int to get a new matrix with zero'd values where assignments is false
        // Replace NaNs in the matrices with 0's on the temp matrix
        tempMatrix.assign(matrixCoordsX) // Assign matrix coords x to temp matrix
        val xAvgTemp = tempMatrix.muli(ciAssignmentsFloat)
        BooleanIndexing.replaceWhere(xAvgTemp, 0.0, Conditions.isNan)
        val xAverage = floor(
          xAvgTemp.sumNumber().floatValue() / center.nVoxels).toInt

        tempMatrix.assign(matrixCoordsY) // Assign matrix coords y to temp matrix
        val yAvgTemp = tempMatrix.muli(ciAssignmentsFloat)
        BooleanIndexing.replaceWhere(yAvgTemp, 0.0, Conditions.isNan)
        val yAverage = floor(
          yAvgTemp.sumNumber().floatValue() / center.nVoxels).toInt

        tempMatrix.assign(matrixCoordsZ) // Assign matrix coords z to temp matrix
        val zAvgTemp = tempMatrix.muli(ciAssignmentsFloat)
        BooleanIndexing.replaceWhere(zAvgTemp, 0.0, Conditions.isNan)
        val zAverage = floor(
          zAvgTemp.sumNumber().floatValue() / center.nVoxels).toInt

        log.debug("Round[{}] Center[{}] Positions old=["+
          center.x+","+center.y+","+center.z+
          "] new=["+
          xAverage+","+yAverage+","+zAverage+"]", rounds, ci)

        // Record the change from previous center, the change
        // is the distance between old and new positions
        totalCentersChange += Math.sqrt(
          Math.pow(center.x - xAverage, 2) +
          Math.pow(center.y - yAverage, 2) +
          Math.pow(center.z - zAverage, 2))

        // Assign positional x.y.z averages as the new point for the center
        center.x = xAverage
        center.y = yAverage
        center.z = zAverage

        // Loop through all the actual features stored in the matrix and get averages for them
        for (i <- 3 until sDimSize) {

          // Get the feature matrix for current i of all points
          val matrixIthFeature = matrix.get(
            NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.point(i)
          )

          // Multiply each coordinate axis values with the ciAssignments bool matrix
          // casted to an Int to get a new matrix with zero'd values where assignments is false
          // Replace NaNs in the matrices with 0's on the temp matrix
          tempMatrix.assign(matrixIthFeature)
          val matrixIthFeatureTemp = tempMatrix.muli(ciAssignmentsFloat)
          BooleanIndexing.replaceWhere(matrixIthFeatureTemp, 0.0, Conditions.isNan)
          val ithFeatureAvg = matrixIthFeatureTemp.sumNumber().floatValue() / center.nVoxels

          // Set it to the voxel features average vector for the center
          center.voxelFeatureAvgs.putScalar(i - 3, ithFeatureAvg)
        }
      }
      lastChange = totalCentersChange / centerCoords.length
      val t1_calculateCenterAverages = System.nanoTime()
      log.info("Round [{}] center adjustments [{} (s)]",
        rounds,
        (t1_calculateCenterAverages - t0_calculateCenterAverages) / 1E9)
      log.info("Average Center Distance Change [{}]", lastChange)

      val t1_round = System.nanoTime()
      log.info("Round [{}] completed [{} (s)]",
        rounds,
        (t1_round - t0_round) / 1E9)
    }
  }

  /**
    * Center Assignments are not directly forced to form a continuous superpixel.
    * The superpixels are made continuous by this function which reassigned
    * isolated "islands" to the best adjacent superpixel.
    */
  private def enforceConnectivity(): Unit = {

    log.info("Enforcing Connectivity...")

    // TODO: Implement

    log.info("Enforced Connectivity")
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
    * Checks the actual size of the matrix against the dimensions provided by client
    * @return
    */
  private def checkDataMatrixDimensionSizes(matrix: Array[Long]): Boolean = {
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
}
