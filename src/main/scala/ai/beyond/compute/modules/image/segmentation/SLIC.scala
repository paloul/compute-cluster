package ai.beyond.compute.modules.image.segmentation

import org.nd4j.linalg.api.ndarray.INDArray

object SLIC {

}

/**
  * An implementation of the SLIC segmentation algorithm using ND4J matrices.
  *
  * @param matrix Input Matrix to segment. Must be of type INDArray from ND4J and >=2 dimensions
  * @param distFn Function to calculate distance between two data points. This is important when we have >1 features
  * @param rollingAvgFn Function to compute rolling average of a series of data points.
  * @param superPixelSize Preferred size of super pixels.
  * @param minSuperPixelSize Minimum size of super pixels. If smaller than this value then
  *                          a super pixel is ingested into larger adjacent super pixel.
  * @param compactness Balances color proximity and space proximity. Higher values give more
  *                    weight to space proximity, making superpixel shapes more square/cubic.
  * @param maxIterations Maximum number of iterations of k-means before result is returned.
  * @param minChangePerIteration Minimum number of cluster center change between interactions to avoid deadlock.
  * @param userClusterNormalization Weigh spatial distance versus value distance by normalizing the value distance
  * @tparam A
  */
class SLIC[A] (
                matrix: INDArray,
                distFn: (A, A) => Float,
                rollingAvgFn: ((A, A, Int) => A),
                superPixelSize: Int,
                minSuperPixelSize: Int = -1,
                compactness: Float = 10,
                maxIterations: Int = 20,
                minChangePerIteration: Float = 0.001f,
                userClusterNormalization: Boolean = false
              ) {

}
