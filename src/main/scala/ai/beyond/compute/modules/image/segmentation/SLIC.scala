package ai.beyond.compute.modules.image.segmentation


import org.nd4j.linalg.api.ndarray._
import org.nd4j.linalg.factory.Nd4j


object slic {

  /**
    * Segments image using k-means clustering in Color-(x,y,z) space. Tailored for
    * Scala and the nd4j library. Based on scikit-image segmentation slic module.
    *
    * @param matrix              Input Matrix to segment. 4D INDArray from ND4J. Matrix is a 3D
    *                            representation with the 4th dimension a collection of properties,
    *                            i.e. RGB values, or more appropriately Permeability, Porosity, Fault, etc.
    *                            The last axis of the matrix is always interpreted as data channels
    * @param numSegments         The (approximate) number of labels in the segmented output image.
    * @param compactness         Balances color proximity and space proximity. Higher values give
    *                            more weight to space proximity, making superpixel shapes more
    *                            square/cubic. In SLICO mode, this is the initial compactness.
    *                            This parameter depends strongly on image contrast and on the
    *                            shapes of objects in the image. We recommend exploring possible
    *                            values on a log scale, e.g., 0.01, 0.1, 1, 10, 100, before
    *                            refining around a chosen value.
    * @param maxIteration        Maximum number of iterations of k-means.
    * @param spacing             The voxel spacing along each image dimension. By default, `slic`
    *                            assumes uniform spacing (same voxel resolution along z, y and x).
    *                            This parameter controls the weights of the distances along z, y,
    *                            and x during k-means clustering. Should always be an ND4j array
    *                            with exactly three columns.
    * @param enforceConnectivity Whether the generated segments are connected or not
    * @param minSizeFactor       Proportion of the minimum segment size to be removed with respect
    *                            to the supposed segment size ```depth*width*height/n_segments```
    * @param maxSizeFactor       Proportion of the maximum connected segment size. A value of 3 works
    *                            in most of the cases.
    * @param runSlicZero         Run SLIC-zero, the zero-parameter mode of SLIC.
    * @return                    3D INDArray of integers indicating segment labels
    */
  def getSegments (
            matrix: INDArray,
            numSegments: Int = 100,
            compactness: Float = 10f,
            maxIteration: Int = 10,
            spacing: INDArray = Nd4j.ones(3),
            enforceConnectivity: Boolean = true,
            minSizeFactor: Float = 1f,
            maxSizeFactor: Float = 20f,
            runSlicZero: Boolean = false
          ) (implicit log: akka.event.LoggingAdapter): INDArray = {

    log.info("Starting SLIC...")

    // Introduce any checks here, add the functions calls to the list
    // Each function call will be evaluated and results stored in checks list
    val checks = List[Boolean](
      checkDimensions(matrix),
      checkSpacing(spacing)
    )

    // Loop through the checks list and make sure everything passed
    // If checks passed then execute the core _getSegments func
    // list.forall(identity) will fail on first instance of FALSE
    if (checks.forall(identity)) {
      // TODO: Do stuff here
      _getSegments(matrix)
    } else {
      // If any checks failed then reply back with empty INDArray
      log.error("SLIC Parameter Checks failed. Stopping.")
      Nd4j.empty()
    }
  }

  /**
    * Initializes superpixel segment centers to a uniform grid based on dimensions
    * @param dims
    * @param gridInterval
    * @return Array of
    */
  private def initSuperCenters(dims: Array[Int], gridInterval: Int): Array[(Int, Int, Int)] = {
    import scala.math._

    val xDim = dims.apply(0)
    val yDim = dims.apply(1)
    val zDim = dims.apply(2)

    val xStart: Int = if (xDim <= gridInterval) floor(xDim / 2).toInt else round(gridInterval / 2f)
    val yStart: Int = if (yDim <= gridInterval) floor(yDim / 2).toInt else round(gridInterval / 2f)
    val zStart: Int = if (zDim <= gridInterval) floor(zDim / 2).toInt else round(gridInterval / 2f)

    val out = for {
      x_s <- xStart until xDim by gridInterval;
      y_s <- yStart until yDim by gridInterval;
      z_s <- zStart until zDim by gridInterval;
    } yield {
      (x_s, y_s, z_s)
    }

    out.toArray
  }

  /**
    * The main private function that does the SLIC algorithm
    * @param matrix
    * @param log Implicitly provided logger
    * @return 3D INDArray of integers indicating segment labels
    */
  private def _getSegments (matrix: INDArray)(implicit log: akka.event.LoggingAdapter): INDArray = {
    Nd4j.zeros(1,1,1)
  }

  /**
    * Checks Dimensions of the given matrix
    * @param matrix
    * @param log Implicitly provided logger
    * @return True if matrix dimensions equal 4, false for anything else
    */
  private def checkDimensions(matrix: INDArray)(implicit log: akka.event.LoggingAdapter): Boolean = {
    if (matrix.shape().length == 4) {
      true
    } else {
      log.warning("SLIC expects Matrix with 4 dimensions. " +
        "Given Matrix only has [{}]", matrix.shape().length)
      false
    }
  }

  /**
    * Checks the provided spacing array for correct size
    * @param spacing
    * @param log
    * @return True if columns equal 3, false for anything else
    */
  private def checkSpacing(spacing: INDArray)(implicit log: akka.event.LoggingAdapter): Boolean = {
    if (spacing.columns() == 3) {
      true
    } else {
      log.warning("SLIC expects Spacing as an INDArray of length 3." +
        "Given Spacing only has [{}] columns", spacing.columns())
      false
    }
  }
}
