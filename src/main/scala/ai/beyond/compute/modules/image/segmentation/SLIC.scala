package ai.beyond.compute.modules.image.segmentation

import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.factory.Nd4j

object slic {

  /**
    * Segments image using k-means clustering in Color-(x,y,z) space. Tailored for
    * Scala and the nd4j library. Based on scikit-image segmentation slic module.
    *
    * @param matrix              Input Matrix to segment. 4D INDArray from ND4J.
    * @param shape               The size of each dimension of the given matrix. Regular Scala Array.
    * @param numSegments         The (approximate) number of labels in the segmented output image.
    * @param compactness         Balances color proximity and space proximity. Higher values give
    *                            more weight to space proximity, making superpixel shapes more
    *                            square/cubic. In SLICO mode, this is the initial compactness.
    *                            This parameter depends strongly on image contrast and on the
    *                            shapes of objects in the image. We recommend exploring possible
    *                            values on a log scale, e.g., 0.01, 0.1, 1, 10, 100, before
    *                            refining around a chosen value.
    * @param maxIteration        Maximum number of iterations of k-means.
    * @param sigma               Width of Gaussian smoothing kernel for pre-processing for each
    *                            dimension of the image. The same sigma is applied to each dimension in
    *                            case of a scalar value. Zero means no smoothing.
    *                            Note, that `sigma` is automatically scaled if it is scalar and a
    *                            manual voxel spacing is provided (see Notes section).
    * @param spacing             The voxel spacing along each image dimension. By default, `slic`
    *                            assumes uniform spacing (same voxel resolution along z, y and x).
    *                            This parameter controls the weights of the distances along z, y,
    *                            and x during k-means clustering.
    * @param multichannel        Whether the last axis of the image is to be interpreted as multiple
    *                            channels or another spatial dimension.
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
            sigma: Array[Float] = Array(0, 0, 0),
            spacing: Array[Float] = Array(0, 0, 0),
            multichannel: Boolean = true,
            enforceConnectivity: Boolean = true,
            minSizeFactor: Float = 0.5f,
            maxSizeFactor: Float = 3f,
            runSlicZero: Boolean = false
          ) (implicit log: akka.event.LoggingAdapter): INDArray = {

    log.info("Starting SLIC...")

    // Introduce any checks here, add the functions calls to the list
    // Each function call will be evaluated and results stored in checks list
    val checks = List[Boolean](
      checkDimensions(matrix)
    )

    // Loop through the checks list and make sure everything passed
    // If checks passed then execute the core _getSegments func
    if (checks.forall(identity)) {
      _getSegments(matrix)
    } else {
      // If any checks failed then reply back with empty INDArray
      log.error("SLIC Parameter Checks failed. Stopping.")
      Nd4j.empty()
    }
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
      log.warning("SLIC expects Matrix with 4 dimensions. Given Matrix only has [{}]", matrix.shape().length)
      false
    }
  }
}
