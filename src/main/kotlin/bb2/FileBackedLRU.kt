package bb2

import ch.systemsx.cisd.base.mdarray.MDShortArray
import ch.systemsx.cisd.hdf5.HDF5Factory
import ch.systemsx.cisd.hdf5.IHDF5Reader
import coremem.ContiguousMemoryInterface
import coremem.buffers.ContiguousBuffer
import coremem.offheap.OffHeapMemory
import io.scif.SCIFIO
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import javax.swing.JFileChooser

/**
 *

 * @author Ulrik Günther @ulrik.is>
 */

open class FileBackedLRU  {
    private val mDriftAmplitude = 0.5

    protected val mSCIFIO: SCIFIO
    protected var readers: ArrayList<IHDF5Reader> = ArrayList()
    @Volatile protected var mCurrentReaderIndex = 0

    protected var mResolutionX: Int = 0
    protected var mResolutionY: Int = 0
    protected var mResolutionZ: Int = 0
    protected var mTotalNumberOfTimePoints: Int = 0

    @Volatile private var mStageX: Float = 0.toFloat()
    @Volatile private var mStageY: Float = 0.toFloat()
    @Volatile private var mStageZ: Float = 0.toFloat()

    @Volatile private var mDriftX: Float = 0.toFloat()
    @Volatile private var mDriftY: Float = 0.toFloat()
    @Volatile private var mDriftZ: Float = 0.toFloat()

    @Volatile private var mCenterLockX: Float = 0.toFloat()
    @Volatile private var mCenterLockY: Float = 0.toFloat()
    @Volatile private var mCenterLockZ: Float = 0.toFloat()

    protected var mVolumeDataArray: ByteBuffer? = null
    protected var mTranslatedVolumeDataArray: ByteBuffer? = null

    @Volatile private var mFirstCall = true

    @Volatile var isCorrectionActive = true

    init {
        mSCIFIO = SCIFIO()
    }

    val resolution: FloatArray
        get() = floatArrayOf(mResolutionX.toFloat(), mResolutionY.toFloat(), mResolutionZ.toFloat(), mTotalNumberOfTimePoints.toFloat())

    fun moveStage(dX: Float, dY: Float, dZ: Float) {
        System.out.format("Moving stage by dx=%g, dy=%g, dz=%g \n",
                dX,
                dY,
                dZ)
        mStageX += dX
        mStageY += dY
        mStageZ += dZ
    }

    private fun setCenterLock(pCenterX: Float,
                              pCenterY: Float,
                              pCenterZ: Float) {
        System.out.format("Set center lock to x=%g, y=%g, z=%g \n",
                pCenterX,
                pCenterY,
                pCenterZ)
        mCenterLockX = pCenterX
        mCenterLockY = pCenterY
        mCenterLockZ = pCenterZ
    }

    fun use4DStacksFromDirectory(pImagesDirectory: String?) {
        var pImagesDirectory = pImagesDirectory
        val pImageDir: File

        if (pImagesDirectory != null) {
            pImageDir = File(pImagesDirectory)
            if (!pImageDir.exists() || !pImageDir.isDirectory()) {
                pImagesDirectory = null
            }
        }

        if (pImagesDirectory == null) {
            val chooser = JFileChooser()
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)

            val returnVal = chooser.showOpenDialog(null)
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                System.out.println("You chose to open this file: " + chooser.getSelectedFile().getName())
            } else {
                return
            }
            pImagesDirectory = chooser.getSelectedFile().getAbsolutePath()
        }

        val folder = File(pImagesDirectory)
        val filesInFolder = folder.listFiles()
        val fileList = ArrayList<String>()

        for (f in filesInFolder) {
            if (f.exists() && f.isFile()
                    && f.canRead()
                    && f.getName().toLowerCase().endsWith(".h5")) {
                System.out.println("Found HDF5: " + f.getName())
                fileList.add(f.getAbsolutePath())
            }
        }

//        tiffFiles.sort { lhs, rhs ->
//            val lhs_tp = lhs.substring(lhs.indexOf("TP")+2, lhs.indexOf("_Ch")).toInt()
//            val rhs_tp = rhs.substring(rhs.indexOf("TP")+2, rhs.indexOf("_Ch")).toInt()
//
//            lhs_tp - rhs_tp
//        }

        mTotalNumberOfTimePoints = fileList.size

        try {
            for (file in fileList) {

                val reader: IHDF5Reader = HDF5Factory.openForReading( file )
                readers.add(reader)

            }
        } catch (e: io.scif.FormatException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        this.mResolutionX = 1241/2
        this.mResolutionY = 1330/2
        this.mResolutionZ = 1329/2

    }


    fun queryNextVolume(): ContiguousMemoryInterface {
        val start = System.currentTimeMillis()
        val reader = readers.get(mCurrentReaderIndex)

//        val block: MDShortArray = reader.int16().readMDArray( "t${String.format("%05d", mCurrentReaderIndex)}/s00/1/cells" )
        val block: MDShortArray = reader.int16().readMDArrayBlockWithOffset("t${String.format("%05d", mCurrentReaderIndex)}/s00/1/cells" , intArrayOf(mResolutionZ, mResolutionY, mResolutionX), longArrayOf(0, 0, 0));

        val lBuffer: ContiguousMemoryInterface = OffHeapMemory.allocateBytes(mResolutionX*mResolutionY*mResolutionZ.toLong()*2)
        System.err.println("buffer size ${lBuffer.sizeInBytes}")//, ${block.dimensions().joinToString("x")}")
        val lContiguousBuffer = ContiguousBuffer(lBuffer)

        System.err.println("Converting buffer...")
        (0..block.size()-1).forEach {
            lContiguousBuffer.writeShort(block.get(it))
        }
        val end = System.currentTimeMillis()
        System.err.println("R: ${reader.file().file.name} ${end-start} ms, ${(lBuffer.sizeInBytes/1024/1024)/((end-start)/1000.0f)} MiB/s")

        mCurrentReaderIndex++
        if(mCurrentReaderIndex > readers.size-1) {
            mCurrentReaderIndex = 0
        }

        lContiguousBuffer.rewind()
        return lBuffer
    }

}
