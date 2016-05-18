/*
 * #%L
 * BigDataViewer core classes with minimal dependencies
 * %%
 * Copyright (C) 2012 - 2015 BigDataViewer authors
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package bb2

import ch.systemsx.cisd.hdf5.IHDF5Reader
import ch.systemsx.cisd.hdf5.hdf5lib.H5D.*
import ch.systemsx.cisd.hdf5.hdf5lib.H5S.*
import ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.*
import java.util.*

/**
 * Access chunked data-sets through lower-level HDF5. This avoids opening and
 * closing the dataset for each chunk when accessing through jhdf5 (This is a
 * huge bottleneck when accessing many small chunks).

 * The HDF5 fileId is extracted from a jhdf5 HDF5Reader using reflection to
 * avoid having to do everything ourselves.

 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
class HDF5AccessHack @Throws(ClassNotFoundException::class, SecurityException::class, NoSuchFieldException::class, IllegalArgumentException::class, IllegalAccessException::class)
constructor(private val hdf5Reader: IHDF5Reader) {

    private val fileId: Int

    private val numericConversionXferPropertyListID: Int

    private val reorderedDimensions = LongArray(3)

    private val reorderedMin = LongArray(3)

    private inner class OpenDataSet(cellsPath: String) {
        internal val dataSetId: Int

        internal val fileSpaceId: Int

        init {
            dataSetId = H5Dopen(fileId, cellsPath, H5P_DEFAULT)
            fileSpaceId = H5Dget_space(dataSetId)
        }

        fun close() {
            H5Sclose(fileSpaceId)
            H5Dclose(dataSetId)
        }
    }

    private inner class OpenDataSetCache : LinkedHashMap<String, OpenDataSet>(MAX_OPEN_DATASETS, 0.75f, true) {

        /*protected fun removeEldestEntry(eldest: Entry<String, OpenDataSet>?): Boolean {
            if (size > MAX_OPEN_DATASETS) {
                eldest!!.value.close()
                return true
            } else
                return false
        }*/

        fun getDataSet(path: String): OpenDataSet {
            var openDataSet: OpenDataSet? = super.get(path)
            if (openDataSet == null) {
                openDataSet = OpenDataSet(path)
                put(path, openDataSet)
            }
            return openDataSet
        }
    }

    private val openDataSetCache: OpenDataSetCache

    init {

        val k = Class.forName("ch.systemsx.cisd.hdf5.HDF5Reader")
        val f = k.getDeclaredField("baseReader")
        f.isAccessible = true
        val baseReader = f.get(hdf5Reader)

        val k2 = Class.forName("ch.systemsx.cisd.hdf5.HDF5BaseReader")
        val f2 = k2.getDeclaredField("fileId")
        f2.isAccessible = true
        fileId = (f2.get(baseReader) as Int).toInt()

        val f3 = k2.getDeclaredField("h5")
        f3.isAccessible = true
        val h5 = f3.get(baseReader)

        val k4 = Class.forName("ch.systemsx.cisd.hdf5.HDF5")
        val f4 = k4.getDeclaredField("numericConversionXferPropertyListID")
        f4.isAccessible = true
        numericConversionXferPropertyListID = (f4.get(h5) as Int).toInt()

        openDataSetCache = OpenDataSetCache()
    }

    @Synchronized @Throws(InterruptedException::class)
    fun readShortMDArrayBlockWithOffset(path: String, dimensions: IntArray, min: LongArray): ShortArray {
        val dataBlock = ShortArray(dimensions[0] * dimensions[1] * dimensions[2])
        readShortMDArrayBlockWithOffset(path, dimensions, min, dataBlock)
        return dataBlock
    }

    @Synchronized @Throws(InterruptedException::class)
    fun readShortMDArrayBlockWithOffset(path: String, dimensions: IntArray, min: LongArray, dataBlock: ShortArray): ShortArray {
        if (Thread.interrupted())
            throw InterruptedException()

        reorderedDimensions[0] = dimensions[0].toLong()
        reorderedDimensions[1] = dimensions[1].toLong()
        reorderedDimensions[2] = dimensions[2].toLong()

        reorderedMin[0] = min[0]
        reorderedMin[1] = min[1]
        reorderedMin[2] = min[2]

        val dataset = openDataSetCache.getDataSet(path)
        val memorySpaceId = H5Screate_simple(reorderedDimensions.size, reorderedDimensions, null)
        H5Sselect_hyperslab(dataset.fileSpaceId, H5S_SELECT_SET, reorderedMin, null, reorderedDimensions, null)
        H5Dread(dataset.dataSetId, H5T_NATIVE_INT16, 0, dataset.fileSpaceId, numericConversionXferPropertyListID, dataBlock)
        H5Sclose(memorySpaceId)

        return dataBlock
    }

    @Throws(InterruptedException::class)
    fun readShortMDArrayBlockWithOffsetAsFloat(path: String, dimensions: IntArray, min: LongArray): FloatArray {
        val dataBlock = FloatArray(dimensions[0] * dimensions[1] * dimensions[2])
        readShortMDArrayBlockWithOffsetAsFloat(path, dimensions, min, dataBlock)
        return dataBlock
    }

    @Throws(InterruptedException::class)
    fun readShortMDArrayBlockWithOffsetAsFloat(path: String, dimensions: IntArray, min: LongArray, dataBlock: FloatArray): FloatArray {
        if (Thread.interrupted())
            throw InterruptedException()

        reorderedDimensions[0] = dimensions[0].toLong()
        reorderedDimensions[1] = dimensions[1].toLong()
        reorderedDimensions[2] = dimensions[2].toLong()

        reorderedMin[0] = 0L
        reorderedMin[1] = 0L
        reorderedMin[2] = 0L

        val dataset = openDataSetCache.getDataSet(path)
        val memorySpaceId = H5Screate_simple(reorderedDimensions.size, reorderedDimensions, null)
        H5Sselect_hyperslab(dataset.fileSpaceId, H5S_SELECT_SET, reorderedMin, null, reorderedDimensions, null)
        H5Dread(dataset.dataSetId, H5T_NATIVE_FLOAT, memorySpaceId, dataset.fileSpaceId, numericConversionXferPropertyListID, dataBlock)
        H5Sclose(memorySpaceId)
        return dataBlock
    }

    fun closeAllDataSets() {
        for (dataset in openDataSetCache.values)
            dataset.close()
        openDataSetCache.clear()
    }

    fun close() {
        closeAllDataSets()
        hdf5Reader.close()
    }

    companion object {

        private val MAX_OPEN_DATASETS = 48
    }

    //	@Override
    //	protected void finalize() throws Throwable
    //	{
    //		try
    //		{
    //			for ( final OpenDataSet dataset : openDataSetCache.values() )
    //				dataset.close();
    //			openDataSetCache.clear();
    //			System.out.println("img.hdf5.HDF5AccessHack.finalize()");
    //			hdf5Reader.close();
    //		}
    //		finally
    //		{
    //			super.finalize();
    //		}
    //	}
}
