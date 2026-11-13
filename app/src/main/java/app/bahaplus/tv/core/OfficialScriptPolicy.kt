package app.bahaplus.tv.core

import java.net.URI
import java.security.MessageDigest

/** Origin and resource boundaries, not a list of compatible versions or filenames.
 * Account HTML, APIs, media and third-party scripts never enter the compiler.
 * The actual provider version sets Babel's output target, not the spoofed UA.
 */
object OfficialScriptPolicy {
    const val COMPILER_VERSION = "babel-7.28.5-corejs-3.47.0-v4"
    const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    private val origins = setOf("i2.bahamut.com.tw", "ani.gamer.com.tw")

    fun major(version: String?): Int = version?.substringBefore('.')?.toIntOrNull() ?: 0

    fun needsTranslation(supportsSyntax: Boolean): Boolean = !supportsSyntax

    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in origins &&
            uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.rawFragment == null && !uri.rawPath.orEmpty().contains('%') &&
            uri.normalize().rawPath == uri.rawPath &&
            (uri.rawPath.orEmpty().endsWith(".js") || uri.rawPath.orEmpty().endsWith(".mjs"))
    }.getOrDefault(false)

    fun cacheKey(source: ByteArray, major: Int): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("$COMPILER_VERSION/chrome$major\u0000".toByteArray(Charsets.UTF_8))
        return hash.digest(source).joinToString("") { "%02x".format(it) }
    }
}
