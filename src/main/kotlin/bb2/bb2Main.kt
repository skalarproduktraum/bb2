package bb2

import clearvolume.renderer.opencl.OpenCLVolumeRenderer
import clearvolume.transferf.TransferFunction1D
import clearvolume.transferf.TransferFunctions
import coremem.types.NativeTypeEnum
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class bb2Main {
    fun main() {
//        val directory = "F:\\ExampleDatasets\\Mette\\005-Lyn-GFP_H2B_mCh-LZ1"
//        val directory = "F:\\ExampleDatasets\\Akanksha"
        val directory = "/Volumes/watson/mpicbg/ExampleData/mette/005-Lyn-GFP_H2B_mCh-LZ1/"
        val cache = FileBackedLRU()
        var res: FloatArray
        var channels = 1

        cache.use4DStacksFromDirectory(directory)
        if(cache.isMultichannel) {
            channels = 2
        }

        val lClearVolumeRenderer = OpenCLVolumeRenderer("bb2 - $directory",
                1024,
                1024,
                NativeTypeEnum.UnsignedShort,
                1024,
                1024,
                channels,
                false);

        lClearVolumeRenderer.setVisible(true)

        val beadEater = TransferFunction1D()
        beadEater.addPoint(0.0, 0.0, 0.0, 1.0)
        beadEater.addPoint(0.8, 0.0, 0.2, 1.0)

        cache.fillCache()

        lClearVolumeRenderer.numberOfRenderLayers = channels
        lClearVolumeRenderer.setVoxelSize(1.0, 1.0, 1.0)

        lClearVolumeRenderer.setTransferFunction(0, TransferFunctions.getGreenGradient())

        if (cache.isMultichannel) {
            lClearVolumeRenderer.setTransferFunction(1, beadEater)
            lClearVolumeRenderer.setLayerVisible(0, true)
            lClearVolumeRenderer.setLayerVisible(1, true)
            lClearVolumeRenderer.setTransferFunctionRangeMax(1, 5000.0)
            lClearVolumeRenderer.setTransferFunctionRangeMin(1, 0.0)

            lClearVolumeRenderer.setGamma(1, 4.0)
        }

        lClearVolumeRenderer.setVisible(true);

        thread {
            while (true) {
                Thread.sleep(cache.getSafeTransitionPeriod())
                cache.fillCache()
            }
        }

        try {

            while (lClearVolumeRenderer.isShowing()) {
                val nextVolume = cache.getNextVolume();
                //val nextVolumeB = cache.queryNextVolume();
                res = cache.resolution


                lClearVolumeRenderer.isVolumeDataUpdateAllowed = false
                if(cache.isMultichannel) {
                    lClearVolumeRenderer.setVolumeDataBuffer(0, TimeUnit.SECONDS, 0, nextVolume[0], res[0].toLong(), res[1].toLong(), res[2].toLong());
                    lClearVolumeRenderer.setVolumeDataBuffer(0, TimeUnit.SECONDS, 1, nextVolume[1], res[0].toLong(), res[1].toLong(), res[2].toLong());
                } else {
                    lClearVolumeRenderer.setVolumeDataBuffer(0, TimeUnit.SECONDS, 0, nextVolume[0], res[0].toLong(), res[1].toLong(), res[2].toLong());
                }
                lClearVolumeRenderer.isVolumeDataUpdateAllowed = true

                lClearVolumeRenderer.waitToFinishAllDataBufferCopy(2, TimeUnit.SECONDS)
                lClearVolumeRenderer.requestDisplay();

                System.out.println("STP=${cache.getSafeTransitionPeriod()}")
                Thread.sleep(cache.getSafeTransitionPeriod()*channels)
                cache.popLastVolume()
            }
        } catch (e: Exception) {
            System.err.println("Exception during volume reading: ");
            e.printStackTrace();
        }

        lClearVolumeRenderer.close();
    }
}