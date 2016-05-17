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
    protected var freeMemory: Long = 0L

    protected var sizes = ArrayList<Long>()
    protected var offHeaps = ArrayList<List<ContiguousMemoryInterface>>()
    protected var keepCount: Int = 0
    protected var keepMax = 20
    protected var filling = false
    protected var readTimes = ArrayList<Long>()

    var isMultichannel = false

    @Volatile private var mFirstCall = true

    @Volatile var isCorrectionActive = true

    init {
        mSCIFIO = SCIFIO()

        System.out.println("Memory status free/total ${Runtime.getRuntime().freeMemory()/1024.0f/1024.0f}/${Runtime.getRuntime().totalMemory()/1024.0f/1024.0f} on ${Runtime.getRuntime().availableProcessors()} processors")
        freeMemory = Runtime.getRuntime().maxMemory();
    }

    val resolution: FloatArray
        get() = floatArrayOf(mResolutionX.toFloat(), mResolutionY.toFloat(), mResolutionZ.toFloat(), mTotalNumberOfTimePoints.toFloat())

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
                System.out.println("Found HDF5: ${f.getName()}, ${f.length()/1024.0f/1024.0f} MiB")
                fileList.add(f.getAbsolutePath().replace("\\", "\\\\"))

                sizes.add(f.length())
            }
        }

        fileList.sort { lhs, rhs ->
            val lhs_tp = lhs.substring(lhs.indexOf("-", lhs.lastIndexOf(File.separator))+1, lhs.lastIndexOf("-")).toInt()
            val rhs_tp = rhs.substring(rhs.indexOf("-", rhs.lastIndexOf(File.separator))+1, rhs.lastIndexOf("-")).toInt()

            lhs_tp - rhs_tp
        }

        mTotalNumberOfTimePoints = fileList.size

        val avg = sizes.reduce { lhs, rhs -> lhs + rhs }/mTotalNumberOfTimePoints

        while(keepCount*avg <= freeMemory-1024*1024*500 && keepCount < keepMax) {
            keepCount++
        }

        System.out.println("Will cache $keepCount volumes, based on available memory")

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

        if(readers[0].exists(datasetPath(0, 1, 1))) {
            isMultichannel = true
        }

    }

    fun fillCache() {
        if(filling) {
            return
        }
        filling = true
        while(offHeaps.size < keepCount) {
            System.out.println("OffHeaps size is ${offHeaps.size}, keepcount=$keepCount")
            offHeaps.add(queryNextVolume())
        }

        filling = false
    }

    fun getNextVolume(): List<ContiguousMemoryInterface> {
        System.err.println("Offheap Count is ${offHeaps.size}")
        return offHeaps.last()
    }

    fun popLastVolume() {
        offHeaps[0].forEach { it.free() }
        offHeaps.removeAt(0)
    }

    fun getSafeTransitionPeriod(): Long {
        if(readTimes.size == 0) {
            return 200
        } else {
            return readTimes.sum() / readTimes.size
        }
    }

    protected fun datasetPath(readerIndex: Int, channel: Int, scaling: Int): String {
       return "t${String.format("%05d", readerIndex)}/s${String.format("%02d", channel)}/$scaling/cells"
    }

    protected fun queryNextVolume(): List<ContiguousMemoryInterface> {
        var start = 0L
        val reader = readers.get(mCurrentReaderIndex)
        var channels = (0..0)
        val buffers = ArrayList<ContiguousMemoryInterface>()

        if(isMultichannel) {
            channels = (0..1)
        }

        channels.forEach { ch ->
            start = System.currentTimeMillis()
            val path = datasetPath(mCurrentReaderIndex, channel = ch, scaling = 1)
            mResolutionX = reader.getDataSetInformation(path).dimensions[0].toInt()
            mResolutionY = reader.getDataSetInformation(path).dimensions[1].toInt()
            mResolutionZ = reader.getDataSetInformation(path).dimensions[2].toInt()

            val block: MDShortArray = reader.int16().readMDArrayBlockWithOffset(path, intArrayOf(mResolutionZ, mResolutionY, mResolutionX), longArrayOf(0, 0, 0));

            val lBuffer: ContiguousMemoryInterface = OffHeapMemory.allocateBytes(mResolutionX * mResolutionY * mResolutionZ.toLong() * 2)
            val lContiguousBuffer = ContiguousBuffer(lBuffer)
            var max: Short = 0

            (0..block.size() - 1).forEach {
                val b = block.get(it)
                max = Math.max(b.toInt(), max.toInt()).toShort()
                lContiguousBuffer.writeShort(block.get(it))
            }
            val end = System.currentTimeMillis()
            System.err.println("R: ${reader.file().file.name} ch $ch ${end - start} ms, ${(lBuffer.sizeInBytes / 1024 / 1024) / ((end - start) / 1000.0f)} MiB/s, ${resolution.joinToString("x")} max: $max")

            readTimes.add(end - start)

            buffers.add(lBuffer)
        }

        mCurrentReaderIndex++
        if(mCurrentReaderIndex > readers.size-1) {
            mCurrentReaderIndex = 0
        }

        return buffers
    }

}
