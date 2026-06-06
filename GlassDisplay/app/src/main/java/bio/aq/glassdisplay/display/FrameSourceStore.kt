package bio.aq.glassdisplay.display

import android.graphics.Bitmap
import bio.aq.glassdisplay.protocol.Transport
import java.util.LinkedHashMap

class FrameSourceStore {
    private val sourceFrames = LinkedHashMap<String, SourceFrame>()
    private var nextSourceOrder = 0

    fun isEmpty(): Boolean = sourceFrames.isEmpty()

    fun ensureSourceFrame(
        sourceId: String,
        transport: Transport,
        width: Int,
        height: Int
    ): SourceFrame {
        val existing = sourceFrames[sourceId]
        if (existing != null && existing.width == width && existing.height == height) {
            existing.transport = transport
            return existing
        }

        val sourceFrame = SourceFrame(
            transport = transport,
            width = width,
            height = height,
            pixels = IntArray(width * height),
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
            order = existing?.order ?: nextSourceOrder++
        )
        sourceFrames[sourceId] = sourceFrame
        return sourceFrame
    }

    fun removeSource(sourceId: String) {
        sourceFrames.remove(sourceId)
    }

    fun currentFrame(): SourceFrame? {
        return sourceFrames.values
            .sortedWith(
                compareBy<SourceFrame> { it.transport != Transport.Tcp }
                    .thenBy { it.order }
            )
            .firstOrNull()
    }

    fun splitFrames(): List<SourceFrame> {
        val bleFrames = sourceFrames.values
            .filter { it.transport == Transport.Ble }
            .sortedBy { it.order }
        if (bleFrames.size >= SPLIT_SOURCE_LIMIT) {
            return bleFrames.take(SPLIT_SOURCE_LIMIT)
        }

        return sourceFrames.values
            .sortedWith(
                compareBy<SourceFrame> { it.transport != Transport.Ble }
                    .thenBy { it.order }
            )
            .take(SPLIT_SOURCE_LIMIT)
    }

    companion object {
        private const val SPLIT_SOURCE_LIMIT = 2
    }
}

data class SourceFrame(
    var transport: Transport,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val bitmap: Bitmap,
    val order: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceFrame) return false

        return transport == other.transport &&
            width == other.width &&
            height == other.height &&
            pixels.contentEquals(other.pixels) &&
            bitmap == other.bitmap &&
            order == other.order
    }

    override fun hashCode(): Int {
        var result = transport.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        result = 31 * result + bitmap.hashCode()
        result = 31 * result + order
        return result
    }
}
