package app.bahaplus.tv.core

import org.json.JSONObject
import java.net.URI

data class QrLoginCode(val link: String, val expiresAt: Long) {
    companion object {
        fun parse(body: JSONObject, now: Long = System.currentTimeMillis() / 1000): QrLoginCode {
            val link = body.getString("link")
            val expires = body.getLong("expiresAt")
            require(link.length <= 2048 && expires > now && expires <= now + 600)
            val uri = URI(link)
            require(uri.scheme == "https" && uri.host == "user.gamer.com.tw" &&
                uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
                uri.path == "/link_login.php" && uri.rawFragment == null &&
                Regex("^token=[A-Za-z0-9._~%+/=-]{32,1536}$").matches(uri.rawQuery.orEmpty()))
            return QrLoginCode(link, expires)
        }
    }
}
