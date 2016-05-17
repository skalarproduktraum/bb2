package bb2

import clearvolume.renderer.factory.ClearVolumeRendererFactory
import clearvolume.transferf.TransferFunctions
import coremem.ContiguousMemoryInterface
import coremem.types.NativeTypeEnum
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class bb2Main {
    val offHeapStores = ArrayList<ContiguousMemoryInterface>()

    fun main() {
        val directory = null
        val lClearVolumeRenderer = ClearVolumeRendererFactory.newOpenCLRenderer("bb2 - $directory",
                1024,
                1024,
                NativeTypeEnum.UnsignedShort,
                1024,
                1024,
                1,
                false);


        lClearVolumeRenderer.setTransferFunction(0, TransferFunctions.getGreenGradient())
//        lClearVolumeRenderer.setTransferFunction(1, TransferFunctions.getGreenGradient())

        lClearVolumeRenderer.setLayerVisible(0, true)
//        lClearVolumeRenderer.setLayerVisible(1, true)
        lClearVolumeRenderer.setVisible(true);

        val cache = FileBackedLRU()
        cache.use4DStacksFromDirectory(directory)

        var res: FloatArray

        lClearVolumeRenderer.setVoxelSize(1.0, 1.0, 1.0);
        cache.fillCache()

        thread {
            while(true) {
                Thread.sleep(500)
                cache.fillCache()
            }
        }

        try {

            while (lClearVolumeRenderer.isShowing()) {
                val nextVolumeA = cache.getNextVolume();
                //val nextVolumeB = cache.queryNextVolume();
                res = cache.resolution

                lClearVolumeRenderer.setVolumeDataBuffer(0, nextVolumeA, res[0].toLong(), res[1].toLong(), res[2].toLong());
                //lClearVolumeRenderer.setVolumeDataBuffer(1, nextVolumeB, res[0].toLong(), res[1].toLong(), res[2].toLong());


                lClearVolumeRenderer.waitToFinishAllDataBufferCopy(2, TimeUnit.SECONDS)
                lClearVolumeRenderer.requestDisplay();

                Thread.sleep(cache.getSafeTransitionPeriod())
                cache.popLastVolume()
            }
        } catch (e: Exception) {
            System.err.println("Exception during volume reading: ");
            e.printStackTrace();
        }

        lClearVolumeRenderer.close();
    }
}