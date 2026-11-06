package app.sloplight.data

import android.content.Context
import android.graphics.Bitmap
import com.limelight.grid.assets.CachedAppAssetLoader.LoaderTuple
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.grid.assets.MemoryAssetLoader
import com.limelight.grid.assets.NetworkAssetLoader
import com.limelight.grid.assets.ScaledBitmap
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * A host's box art: from memory, else the disk cache, else the host (then
 * cached). The host's stand-in art (GFE's 130x180 and 628x888 placeholders)
 * counts as none, so the card shows the app's name instead.
 */
class BoxArt(context: Context, private val computer: ComputerDetails, uniqueId: String) {
    private val memory = MemoryAssetLoader()
    private val disk = DiskAssetLoader(context.applicationContext)
    private val network = NetworkAssetLoader(context.applicationContext, uniqueId)

    /** The memory cache only (no waiting: what a card can show at once) */
    fun cached(app: NvApp): Bitmap? = memory.loadBitmapFromCache(LoaderTuple(computer, app))?.real()

    suspend fun load(app: NvApp, sampleSize: Int = 1): Bitmap? = downloads.withPermit {
        withContext(Dispatchers.IO) {
            val tuple = LoaderTuple(computer, app)
            memory.loadBitmapFromCache(tuple)?.let { return@withContext it.real() }
            var bitmap = disk.loadBitmapFromCache(tuple, sampleSize)
            if (bitmap == null) {
                network.getBitmapStream(tuple)?.use { disk.populateCacheWithStream(tuple, it) }
                bitmap = disk.loadBitmapFromCache(tuple, sampleSize)
            }
            bitmap?.also { memory.populateCache(tuple, it) }?.real()
        }
    }

    private fun ScaledBitmap.real(): Bitmap? =
        if ((originalWidth == 130 && originalHeight == 180) || (originalWidth == 628 && originalHeight == 888)) null else bitmap

    private companion object {
        /** A few at a time (the host serves them one by one anyway) */
        val downloads = Semaphore(4)
    }
}
