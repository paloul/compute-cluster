package ai.beyond.compute.modules.image.segmentation

import org.nd4j.linalg.api.ndarray.INDArray

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
    *
    */
  def getSegments (
            matrix: INDArray,
            shape: Array[Int],
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
          ) (implicit log: akka.event.LoggingAdapter) {

    log.info("Starting SLIC Algorithm...")


  }

  private def _getSegments (): Unit = {

  }
}
