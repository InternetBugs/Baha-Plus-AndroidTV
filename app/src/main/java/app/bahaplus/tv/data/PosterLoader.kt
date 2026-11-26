package app.bahaplus.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import app.bahaplus.tv.core.OfficialSite
import app.bahaplus.tv.core.posterSampleSize
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PosterLoader(context: Context, lowResources: Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val directory = File(context.cacheDir, "posters").apply { mkdirs() }
    private val sequence = AtomicLong()
    private val diskWrites = AtomicInteger()
    private val jobs = java.util.WeakHashMap<ImageView, Future<*>>()
    private val urlLocks = ConcurrentHashMap<String, Any>()
    private val executor = ThreadPoolExecutor(
        if (lowResources) 2 else 3,
        3,
        20,
        TimeUnit.SECONDS,
        LinkedBlockingQueue()
    ).apply { allowCoreThreadTimeOut(true) }
    private val cache = object : LruCache<String, Bitmap>(if (lowResources) 8 * 1024 else 20 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    var lowResources = lowResources
        set(value) {
            field = value
            cache.resize(if (value) 8 * 1024 else 20 * 1024)
            runCatching { executor.corePoolSize = if (value) 2 else 3 }
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
        val job = runCatching {
            executor.submit {
                if (Thread.interrupted() || targetView.get()?.tag != ticket) return@submit
                val bitmap = cache.get(key) ?: runCatching { decodePoster(url, target) }.getOrNull()
                if (bitmap != null) cache.put(key, bitmap)
                if (Thread.interrupted()) return@submit
                handler.post {
                    val currentView = targetView.get()
                    if (currentView?.tag == ticket) {
                        jobs.remove(currentView)
                        if (bitmap != null) currentView.setImageBitmap(bitmap)
                    }
                }
            }
        }.getOrNull()
        if (job == null) view.tag = null else jobs[view] = job
    }

    private fun decodePoster(url: String, target: Int): Bitmap? {
        val hash = sha256(url)
        val lock = urlLocks.computeIfAbsent(hash) { Any() }
        val bytes = try {
            synchronized(lock) {
                if (Thread.interrupted()) return null
                val file = File(directory, hash)
                if (file.isFile && file.length() in 1..MAX_IMAGE_BYTES) {
                    val now = System.currentTimeMillis()
                    if (now - file.lastModified() > LRU_TOUCH_INTERVAL_MS) {
                        file.setLastModified(now)
                    }
                    file.readBytes()
                } else {
                    fetch(url).also { data ->
                        val temporary = File.createTempFile("poster-", ".part", directory)
                        try {
                            temporary.writeBytes(data)
                            if (!temporary.renameTo(file)) temporary.delete()
                        } finally {
                            if (temporary.exists()) temporary.delete()
                        }
                        if (diskWrites.incrementAndGet() % TRIM_INTERVAL == 0) trimDisk()
                    }
                }
            }
        } finally {
            urlLocks.remove(hash, lock)
        }
        if (Thread.interrupted()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = posterSampleSize(bounds.outWidth, bounds.outHeight, target)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
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
                connection.setRequestProperty("User-Agent", OfficialSite.USER_AGENT)
                val code = connection.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), connection.getHeaderField("Location") ?: error("Redirect missing")).toString()
                } else {
                    require(code == 200 && connection.contentType.orEmpty().startsWith("image/"))
                    require(connection.contentLength <= MAX_IMAGE_BYTES)
                    return connection.inputStream.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) error("Cancelled")
                            val read = stream.read(buffer)
                            if (read < 0) break
                            require(output.size() + read <= MAX_IMAGE_BYTES)
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                }
            } finally {
                connection.disconnect()
            }
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
    }

    fun trim() { cache.evictAll() }

    fun destroy() {
        jobs.values.forEach { it.cancel(true) }
        jobs.clear()
        executor.shutdownNow()
        cache.evictAll()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val TRIM_INTERVAL = 12
        private const val LRU_TOUCH_INTERVAL_MS = 5 * 60 * 1000L

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
