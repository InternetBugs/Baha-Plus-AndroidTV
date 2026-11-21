package app.bahaplus.tv.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class OfficialLoginCookiesTest {
    private val token = "BAHA" + "RUNE"
    private val sent = "BAHAID=owner; $token=initial"
    private val uri = URI("https://ani.gamer.com.tw/ajax/getdeviceid.php")
    private var browser: String? = sent
    private val writes = mutableListOf<String>()
    private val store = OfficialLoginCookies(readCookie = { browser }, writeCookie = { _, header ->
        writes += header
        browser = "BAHAID=owner; ${header.substringBefore(';')}"
    })

    private fun persist(epoch: Long? = store.rotationEpoch(), response: String = "$token=rotated; Secure",
        request: String = sent, origin: URI = uri, isCurrent: () -> Boolean = { true }) {
        store.persist(epoch, origin, request, mapOf("set-cookie" to listOf(response)), isCurrent)
    }

    @Test fun lateResponseCannotUndoANewerLoginOrRotation() {
        val original = store.rotationEpoch()
        val owner = Any()
        store.beginLogin(owner)
        assertNull(store.rotationEpoch())
        persist(original)
        persist()
        store.endLogin(owner)
        persist(original)
        assertTrue(writes.isEmpty())

        persist()
        persist(response = "$token=stale; Secure")
        assertEquals(listOf("$token=rotated; Secure"), writes)
        assertEquals("BAHAID=owner; $token=rotated", store.snapshot().getValue(uri.host))
    }

    @Test fun logoutRejectsOldResponsesDuringAndAfterBrowserClear() {
        val original = store.rotationEpoch()
        lateinit var cleared: () -> Unit
        var finished = false
        store.clear(remove = { cleared = it }, after = { finished = true })
        assertTrue(store.isClearing())
        assertTrue(store.snapshot().isEmpty())
        assertNull(store.rotationEpoch())
        persist(original)
        browser = null
        cleared()
        assertTrue(finished)
        assertFalse(store.isClearing())
        browser = sent
        persist(original)
        assertTrue(writes.isEmpty())
    }

    @Test fun onlyTheUnambiguousCurrentAccountCanWriteForOfficialOrigins() {
        persist(request = "$token=initial")
        persist(request = "$sent; $token=other")
        persist(response = "$token=rotated; Domain=example.com; Path=/")
        persist(response = "ANIME_SIGN=guest; Domain=gamer.com.tw; Path=/")
        persist(origin = URI("https://ani.gamer.com.tw:444/"))
        persist(origin = URI("https://ani.gamer.com.tw.example.com/"))
        persist(isCurrent = { false })
        browser = "BAHAID=another; $token=initial"
        persist()
        browser = "$sent; BAHAID=another"
        persist()
        assertTrue(writes.isEmpty())
    }
}
