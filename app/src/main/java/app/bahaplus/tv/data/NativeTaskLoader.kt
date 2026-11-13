package app.bahaplus.tv.data

import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One replaceable operation. Cancellation also retires UI callbacks already queued. */
internal class NativeTaskLoader(private val deliver: (() -> Unit) -> Unit) {
    private class Request(val session: OfficialHttpSession) { var future: Future<*>? = null }
    private val executor = ThreadPoolExecutor(0, 2, 5, TimeUnit.SECONDS, LinkedBlockingQueue())
    @Volatile private var current: Request? = null
    @Volatile private var closed = false

    fun <T> load(session: OfficialHttpSession, operation: (OfficialHttpSession) -> T, done: (Result<T>) -> Unit) {
        cancel()
        if (closed) { session.close(); return }
        val request = Request(session)
        current = request
        request.future = executor.submit {
            val result = try { runCatching { operation(session) } } finally { session.close() }
            if (!closed && current === request) deliver {
                if (!closed && current === request) { current = null; done(result) }
            }
        }
    }

    fun cancel() {
        val previous = current
        current = null
        previous?.session?.close()
        previous?.future?.cancel(true)
        executor.purge()
    }

    fun destroy() { closed = true; cancel(); executor.shutdownNow() }
}
