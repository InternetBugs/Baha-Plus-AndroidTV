package app.bahaplus.tv.data

import android.os.Handler
import android.os.Looper
import app.bahaplus.tv.core.UpdateCheckResult
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A short-lived worker independent of the official site's WebView and account session. */
class GitHubUpdateChecker(private val currentVersion: String) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val client = GitHubReleaseClient()
    @Volatile private var closed = false

    fun check(onResult: (UpdateCheckResult) -> Unit) {
        if (closed) return
        executor.execute {
            val result = client.check(currentVersion)
            if (!closed) handler.post { if (!closed) onResult(result) }
        }
    }

    fun destroy() {
        closed = true
        executor.shutdownNow()
        client.close()
        handler.removeCallbacksAndMessages(null)
    }
}
