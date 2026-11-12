package app.bahaplus.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import app.bahaplus.tv.core.OfficialSite
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PosterLoader(context: Context, lowResources: Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val directory = File(context.cacheDir, "posters").apply { mkdirs() }
    private val sequence = AtomicLong()
    private val jobs = java.util.WeakHashMap<ImageView, Future<*>>()
    private val executor = ThreadPoolExecutor(1, 2, 20, TimeUnit.SECONDS, LinkedBlockingQueue(48),
        ThreadPoolExecutor.AbortPolicy())
    private val cache = object : LruCache<String, Bitmap>(if (lowResources) 8 * 1024 else 20 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    var lowResources = lowResources
        set(value) {
            field = value
            cache.resize(if (value) 8 * 1024 else 20 * 1024)
        }
    fun load(view: ImageView, url: String) {
        cancel(view)
        view.setImageDrawable(null)
        if (!OfficialSite.image(url)) return
        val target = if (lowResources) 320 else 512
        val key = "$target:$url"
        cache.get(key)?.also { view.setImageBitmap(it); return }
        val ticket = sequence.incrementAndGet()
        val targetView = WeakReference(view)
        view.tag = ticket
        val job = runCatching { executor.submit {
            val bitmap = runCatching {
                val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val file = File(directory, hash)
                val bytes = if (file.isFile && file.length() <= 4 * 1024 * 1024) file.readBytes() else fetch(url).also { data ->
                    val temporary = File.createTempFile("poster-", ".part", directory)
                    try {
                        temporary.writeBytes(data)
                        if (!temporary.renameTo(file)) temporary.delete()
                    } finally { if (temporary.exists()) temporary.delete() }
                    trimDisk()
                }
                if (Thread.interrupted()) return@submit
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384)
                var sample = 1
                while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            handler.post {
                val currentView = targetView.get()
                if (currentView?.tag == ticket) {
                    jobs.remove(currentView)
                    if (bitmap != null) currentView.setImageBitmap(bitmap)
                }
            }
        } }.getOrNull()
        if (job == null) view.tag = null else jobs[view] = job
    }
    private fun fetch(raw: String): ByteArray {
        var url = raw
        repeat(4) {
            require(OfficialSite.image(url))
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val code = connection.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), connection.getHeaderField("Location") ?: error("Redirect missing")).toString()
                } else {
                    require(code == 200 && connection.contentType.orEmpty().startsWith("image/"))
                    require(connection.contentLength <= 4 * 1024 * 1024)
                    return connection.inputStream.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) error("Cancelled")
                            val read = stream.read(buffer)
                            if (read < 0) break
                            require(output.size() + read <= 4 * 1024 * 1024)
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }
    @Synchronized private fun trimDisk() {
        val files = directory.listFiles()?.filter { !it.name.endsWith(".part") }?.sortedBy { it.lastModified() }.orEmpty()
        val limit = if (lowResources) 12L * 1024 * 1024 else 32L * 1024 * 1024
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= limit) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }
    fun cancel(view: ImageView) {
        jobs.remove(view)?.cancel(true)
        view.tag = null
        executor.purge()
    }
    fun trim() { cache.evictAll() }
    fun destroy() {
        jobs.values.forEach { it.cancel(true) }
        jobs.clear()
        executor.shutdownNow()
        cache.evictAll()
        handler.removeCallbacksAndMessages(null)
    }
}
