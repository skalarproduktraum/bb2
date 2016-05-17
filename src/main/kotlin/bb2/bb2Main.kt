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
        val directory = "/Volumes/watson/mpicbg/ExampleData/mette/005-Lyn-GFP_H2B_mCh-LZ1/"
        val lClearVolumeRenderer = ClearVolumeRendererFactory.newOpenCLRenderer("bb2 - $directory",
                1024,
                1024,
                NativeTypeEnum.UnsignedShort,
                1024,
                1024,
                1,
                false);


        lClearVolumeRenderer.setTransferFunction(0, TransferFunctions.getRainbowSolid())
//        lClearVolumeRenderer.setTransferFunction(1, TransferFunctions.getGreenGradient())

        lClearVolumeRenderer.setLayerVisible(0, true)
//        lClearVolumeRenderer.setLayerVisible(1, true)
        lClearVolumeRenderer.setVisible(true);

        val cache = FileBackedLRU()
        cache.use4DStacksFromDirectory(directory)

        val res: FloatArray = cache.resolution
        System.out.println("Resolution: ${res.joinToString("x")}")

        lClearVolumeRenderer.setVoxelSize(1.0, 1.0, 1.0);

        thread {
            Thread.sleep(1000*10)

            while(offHeapStores.isNotEmpty()) {
                offHeapStores[0].free()
                offHeapStores.remove(offHeapStores[0])
                Thread.sleep(5*1000)
            }
        }

        try {

            while (lClearVolumeRenderer.isShowing()) {
                val nextVolumeA = cache.queryNextVolume();
                //val nextVolumeB = cache.queryNextVolume();

                lClearVolumeRenderer.setVolumeDataBuffer(0, nextVolumeA, res[0].toLong(), res[1].toLong(), res[2].toLong());
                //lClearVolumeRenderer.setVolumeDataBuffer(1, nextVolumeB, res[0].toLong(), res[1].toLong(), res[2].toLong());

                offHeapStores.add(nextVolumeA)

                lClearVolumeRenderer.waitToFinishAllDataBufferCopy(2, TimeUnit.SECONDS)
                lClearVolumeRenderer.requestDisplay();
            }
        } catch (e: Exception) {
            System.err.println("Exception during volume reading: ");
            e.printStackTrace();
        }

        lClearVolumeRenderer.close();
    }
}