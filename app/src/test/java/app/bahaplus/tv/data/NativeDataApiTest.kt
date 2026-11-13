package app.bahaplus.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class NativeDataApiTest {
    private class Response(val uri: URI, val body: String, val status: Int = 200,
        val headers: Map<String?, List<String>> = emptyMap(), val length: Int = -1) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        val output = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = output
        override fun getHeaderFields() = headers
        override fun getHeaderField(name: String): String? = headers.entries.firstOrNull {
            it.key.equals(name, true)
        }?.value?.firstOrNull()
        override fun getContentLength() = length
    }

    @Test fun catalogMergesSectionsWithoutLosingTimelineVideo() {
        fun row(id: Int, video: Int = 0) = JSONObject().put("animeSn", id).put("title", "動畫$id")
            .put("cover", "https://p2.bahamut.com.tw/$id.jpg").put("videoSn", video)
            .put("volume", "第 2 集").put("info", "共 12 集")
        val data = JSONObject().put("newAnime", JSONObject().put("date", JSONArray().put(row(1, 10))))
            .put("hotAnime", JSONArray().put(row(1)).put(row(2)))
            .put("newAdded", JSONArray().put(row(3)))
        val values = NativeCatalogApi.parseCatalog(data)
        assertEquals(listOf("1", "2", "3"), values.map { it.id })
        assertEquals(listOf("season", "hot"), values[0].sections)
        assertEquals("10", values[0].latestVideoId)
        assertEquals("第 2 集", values[0].episodeLabel)
        assertEquals("共 12 集", values[1].episodeLabel)
    }

    @Test fun searchMergesBothQueriesAndNeverSendsAccountCookies() {
        val requests = mutableListOf<Response>()
        val session = OfficialHttpSession(openConnection = { uri ->
            Response(uri, """{"data":{"animeList":[{"animeSn":1,"videoSn":10,"title":"動畫",
              "cover":"https://p2.bahamut.com.tw/a.jpg","totalEpisode":12}]}}""").also { requests += it }
        })
        assertEquals(1, NativeCatalogApi(session).search(listOf("动画", "動畫")).size)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.getRequestProperty("Cookie") == null && !it.instanceFollowRedirects })
        assertTrue(requests.all { it.disconnected })
    }

    @Test fun invalidCatalogImagesAndOversizedSearchListsFail() {
        for (cover in listOf("http://p2.bahamut.com.tw/a", "https://user@p2.bahamut.com.tw/a",
            "https://p2.bahamut.com.tw.evil.invalid/a", "https://p2.bahamut.com.tw:444/a")) {
            val row = JSONObject().put("animeSn", 1).put("title", "動畫").put("cover", cover)
            assertThrows(Exception::class.java) {
                NativeCatalogApi.parseSearch(JSONObject().put("animeList", JSONArray().put(row)))
            }
        }
        assertThrows(Exception::class.java) {
            NativeCatalogApi.parseSearch(JSONObject().put("animeList", JSONArray(List(5001) { JSONObject() })))
        }
    }

    @Test fun inertMarkupHandlesQuotedDelimitersEntitiesAndDiscardsActiveContent() {
        val doc = InertHtml.parse("""<!-- <a class="user-info">fake</a> -->
            <script>var x = '<a class="user-info">forged</a>';</script><style>.bad{}</style>
            <a class='user-info' title='x > y'><b class="user-id">owner</b><span class='user-name'>A &amp; B &#x1F41F;</span></a>
            <div class="avatar"><img data-src='https://p2.bahamut.com.tw/a?x=1&amp;y=2'></div>""")
        assertEquals("owner" to "A & B 🐟", NativeAccountApi.profile(doc))
        assertEquals(1, doc.select("a.user-info").size)
        assertEquals("x > y", doc.selectFirst("a.user-info")?.attr("title"))
        assertEquals("https://p2.bahamut.com.tw/a?x=1&y=2", doc.selectFirst(".avatar img")?.attr("data-src"))
        assertTrue(doc.select("script, style").isEmpty())
        assertEquals("\ufffd", InertHtml.decode("&#xD800;"))
        assertThrows(Exception::class.java) { InertHtml.parse("<div>".repeat(129)) }
    }

    @Test fun accountRequiresAnExplicitGuestMarkerOrAValidProfile() {
        assertNull(NativeAccountApi.profile(InertHtml.parse("<a href='https://user.gamer.com.tw/login.php'>登录</a>")))
        assertThrows(Exception::class.java) { NativeAccountApi.profile(InertHtml.parse("<html>changed</html>")) }
        assertThrows(Exception::class.java) {
            NativeAccountApi.profile(InertHtml.parse(profile("bad/owner")))
        }
    }

    @Test fun serverRenderedOwnerConfirmsLoginWithoutTheJavascriptUserMenu() {
        val requests = mutableListOf<String>()
        val session = OfficialHttpSession(mapOf("ani.gamer.com.tw" to
            "BAHAID=owner; BAHANICK=%E6%B8%AC%E8%A9%A6%20A+B%20%26amp%3B%20C"),
            openConnection = { uri ->
                requests += uri.path
                assertEquals("/mygather.php", uri.path)
                Response(uri, collectionPage("owner") +
                    "<script>var fake = '<a class=\"user-info\">ignore</a>';</script>")
            })
        assertEquals(OfficialIdentity("owner", "測試 A+B & C"), NativeAccountApi(session).identity())
        assertEquals(listOf("/mygather.php"), requests)
    }

    @Test fun collectionOwnerCannotComeFromGuestUntrustedOrAmbiguousLinks() {
        for (link in listOf("https://evil.invalid/viewList.php?u=owner",
            "http://ani.gamer.com.tw/viewList.php?u=owner",
            "https://ani.gamer.com.tw:444/viewList.php?u=owner",
            "https://user@ani.gamer.com.tw/viewList.php?u=owner",
            "/viewList.php?u=owner#fragment", "/viewList.php?u=owner&u=other",
            "/viewList.php?u=guest", "/viewList.php?u=bad%2Fowner")) {
            assertThrows(NativeApiException::class.java) {
                NativeAccountApi.profile(InertHtml.parse(
                    "<h2 class='theme-title'>我的動畫</h2><a href='$link'>歷史</a>"))
            }
        }
        assertThrows(NativeApiException::class.java) {
            NativeAccountApi.profile(InertHtml.parse("<a href='/viewList.php?u=owner'>歷史</a>"))
        }
        assertThrows(NativeApiException::class.java) {
            NativeAccountApi.profile(InertHtml.parse(collectionPage("owner") +
                "<a href='https://user.gamer.com.tw/login.php'>登入</a>"))
        }
    }

    @Test fun conflictingServerOwnersAndMenuIdentityFailClosed() {
        for (html in listOf(collectionPage("owner") + "<a href='/viewList.php?u=other'>歷史</a>",
            profile("other") + collectionPage("owner"))) {
            assertEquals("account-stale", assertThrows(NativeApiException::class.java) {
                NativeAccountApi.profile(InertHtml.parse(html))
            }.code)
        }
        assertEquals("Owner", NativeAccountApi.profile(InertHtml.parse(collectionPage("Owner") +
            "<a href='/viewList.php?u=owner'>歷史</a>"))?.first)
    }

    @Test fun nicknameCookiesAreOnlyHintsForTheServerVerifiedOwner() {
        for (header in listOf("BAHAID=other; BAHANICK=wrong", "BAHANICK=wrong",
            "BAHAID=owner; BAHANICK=%ZZ", "BAHAID=owner; BAHANICK=%0Awrong",
            "BAHAID=owner; BAHANICK=${"x".repeat(201)}")) {
            val session = OfficialHttpSession(mapOf("ani.gamer.com.tw" to header),
                openConnection = { uri -> Response(uri, collectionPage("owner")) })
            assertEquals(OfficialIdentity("owner", "owner"), NativeAccountApi(session).identity())
        }
        val guest = OfficialHttpSession(mapOf("ani.gamer.com.tw" to "BAHAID=owner; BAHANICK=hint"),
            openConnection = { uri -> Response(uri,
                "<a href='https://user.gamer.com.tw/login.php'>登入</a>") })
        assertNull(NativeAccountApi(guest).identity())
    }

    @Test fun identityIsAvailableBeforeOptionalAccountDataCanFail() {
        val requests = mutableListOf<String>()
        val session = OfficialHttpSession(openConnection = { uri ->
            requests += uri.path
            Response(uri, if (uri.path.endsWith("history.php")) """{"error":{"code":500}}"""
                else collectionPage("owner"))
        })
        val api = NativeAccountApi(session)
        assertEquals(OfficialIdentity("owner", "owner"), api.identity())
        assertEquals(listOf("/mygather.php"), requests)
        assertEquals("official-rejected", assertThrows(NativeApiException::class.java) {
            api.account("owner")
        }.code)
    }

    @Test fun dataSyncRejectsAChangedOwnerBeforeFetchingCollectionsOrHistory() {
        val paths = mutableListOf<String>()
        val session = OfficialHttpSession(openConnection = { uri ->
            paths += uri.path
            Response(uri, collectionPage("other"))
        })
        assertEquals("account-stale", assertThrows(NativeApiException::class.java) {
            NativeAccountApi(session).account("owner")
        }.code)
        assertEquals(listOf("/mygather.php"), paths)
    }

    @Test fun accountPaginatesDeduplicatesAndSkipsWithdrawnHistory() {
        val requested = mutableListOf<URI>()
        val session = OfficialHttpSession(mapOf("ani.gamer.com.tw" to "session=private"),
            openConnection = { uri ->
                requested += uri
                Response(uri, when {
                    uri.path.endsWith("history.php") -> """{"data":{"totalPage":1,"history":[
                      {"isTakenDown":1},{"animeSn":1,"videoSn":10,"title":"動畫","cover":"https://p2.bahamut.com.tw/a.jpg",
                       "episode":"1","duration":24,"breakPoint":{"breakPoint":720}}]}}"""
                    uri.query != null -> profile("owner") + "<h2 class='theme-title'>我的動畫</h2>" + card("2")
                    else -> profile("owner") + "<h2 class='theme-title'>我的動畫</h2>" + card("1") +
                        "<div class='page_number'><a href='/mygather.php?page=2'>2</a>" +
                        "<a href='https://evil.invalid/mygather.php?page=3'>3</a></div>"
                })
            })
        val value = NativeAccountApi(session).account()!!
        assertEquals("owner", value.id)
        assertEquals(listOf("1", "2"), value.favorites.map { it.id })
        assertEquals(.5, value.history.single().progress!!, .00001)
        assertEquals("12:00", value.history.single().progressLabel)
        assertTrue(requested.none { it.host == "evil.invalid" })
        assertEquals(1, requested.count { it.query == "page=2" && it.path == "/mygather.php" })
    }

    @Test fun ownerChangeDuringPaginationCannotPublishAccountData() {
        val session = OfficialHttpSession(openConnection = { uri ->
            Response(uri, profile(if (uri.query == null) "owner" else "other") +
                "<h2 class='theme-title'>我的動畫</h2><div class='page_number'><a href='?page=2'>2</a></div>")
        })
        assertThrows(NativeApiException::class.java) { NativeAccountApi(session).account() }
    }

    @Test fun favoriteUsesMatchingSeriesAndCsrfWithoutExecutingControls() {
        val requests = mutableListOf<Response>()
        var selected = false
        val session = OfficialHttpSession(openConnection = { uri ->
            Response(uri, when (uri.path) {
                "/mygather.php" -> profile("owner")
                "/search.php" -> "<button onclick=\"toggleGather(99, 2, this)\"></button>" +
                    control("1", selected)
                "/ajax/getCSRFToken.php" -> "csrf-value"
                "/ajax/want2play.php" -> {
                    selected = true
                    "<root><okmsg>ok</okmsg></root>"
                }
                else -> error("unexpected endpoint")
            }).also { requests += it }
        })
        val anime = NativeAccountApi.parseFavorites(InertHtml.parse(card("1"))).single()
        NativeAccountApi(session).favorite(anime, true, "owner")
        val post = requests.single { it.uri.path == "/ajax/want2play.php" }
        assertEquals("POST", post.requestMethod)
        assertEquals("s=10&animeSn=1&token=csrf-value", post.output.toString("UTF-8"))
        assertEquals(2, requests.count { it.uri.path == "/search.php" })
    }

    @Test fun removalConfirmsServerStateWithoutRequiringAnIntegerZeroAcknowledgement() {
        for (ack in listOf("""{"error":0}""", """{"error":"0"}""", """{"error":-1}""",
            """{"error":null}""", """{"error":false}""", """{"msg":"ok"}""", "true", "")) {
            for (modern in listOf(false, true)) {
                var selected = true
                var posts = 0
                var reads = 0
                val session = OfficialHttpSession(openConnection = { uri ->
                    Response(uri, when (uri.path) {
                        "/mygather.php" -> collectionPage("owner")
                        "/search.php" -> {
                            reads++
                            control("1", selected, modern)
                        }
                        "/ajax/getCSRFToken.php" -> "csrf-value"
                        "/ajax/delgather.php" -> {
                            posts++
                            selected = false
                            ack
                        }
                        else -> error("unexpected endpoint")
                    })
                })
                NativeAccountApi(session).favorite(testAnime(), false, "owner")
                assertFalse(selected)
                assertEquals(1, posts)
                assertEquals(2, reads)
            }
        }
    }

    @Test fun missingOrSuccessfulAcknowledgementCannotHideAnUnchangedFavorite() {
        for (ack in listOf("""{"error":0}""", """{"error":null}""", "{}", "changed-format", "")) {
            val session = OfficialHttpSession(openConnection = { uri ->
                Response(uri, when (uri.path) {
                    "/mygather.php" -> collectionPage("owner")
                    "/search.php" -> control("1", true)
                    "/ajax/getCSRFToken.php" -> "csrf-value"
                    "/ajax/delgather.php" -> ack
                    else -> error("unexpected endpoint")
                })
            })
            assertEquals("favorite-rejected", assertThrows(NativeApiException::class.java) {
                NativeAccountApi(session).favorite(testAnime(), false, "owner")
            }.code)
        }
    }

    @Test fun positiveAndInvalidRemovalErrorsRemainFailures() {
        for (ack in listOf("""{"error":1}""", """{"error":"2"}""", """{"error":true}""",
            """{"error":"invalid"}""", """{"error":{"code":1}}""")) {
            var searchReads = 0
            val session = OfficialHttpSession(openConnection = { uri ->
                Response(uri, when (uri.path) {
                    "/mygather.php" -> collectionPage("owner")
                    "/search.php" -> control("1", ++searchReads == 1)
                    "/ajax/getCSRFToken.php" -> "csrf-value"
                    "/ajax/delgather.php" -> ack
                    else -> error("unexpected endpoint")
                })
            })
            assertEquals("favorite-rejected", assertThrows(NativeApiException::class.java) {
                NativeAccountApi(session).favorite(testAnime(), false, "owner")
            }.code)
            assertEquals(1, searchReads)
        }
    }

    @Test fun addAcknowledgementAlsoRequiresTheActualServerStateToChange() {
        val session = OfficialHttpSession(openConnection = { uri ->
            Response(uri, when (uri.path) {
                "/mygather.php" -> collectionPage("owner")
                "/search.php" -> control("1", false)
                "/ajax/getCSRFToken.php" -> "csrf-value"
                "/ajax/want2play.php" -> "<root><okMsg>ok</okMsg></root>"
                else -> error("unexpected endpoint")
            })
        })
        assertEquals("favorite-rejected", assertThrows(NativeApiException::class.java) {
            NativeAccountApi(session).favorite(testAnime(), true, "owner")
        }.code)
    }

    @Test fun alreadyDesiredFavoriteStateDoesNotSendAnotherMutation() {
        for (selected in listOf(false, true)) {
            val paths = mutableListOf<String>()
            val session = OfficialHttpSession(openConnection = { uri ->
                paths += uri.path
                Response(uri, when (uri.path) {
                    "/mygather.php" -> collectionPage("owner")
                    "/search.php" -> control("1", selected)
                    else -> error("unexpected mutation")
                })
            })
            NativeAccountApi(session).favorite(testAnime(), selected, "owner")
            assertEquals(listOf("/mygather.php", "/search.php", "/mygather.php"), paths)
        }
    }

    @Test fun ownerChangeAfterMutationCannotConfirmAnotherAccountsFavoriteState() {
        var selected = true
        val session = OfficialHttpSession(openConnection = { uri ->
            Response(uri, when (uri.path) {
                "/mygather.php" -> collectionPage(if (selected) "owner" else "other")
                "/search.php" -> control("1", selected)
                "/ajax/getCSRFToken.php" -> "csrf-value"
                "/ajax/delgather.php" -> {
                    selected = false
                    "{}"
                }
                else -> error("unexpected endpoint")
            })
        })
        assertEquals("account-stale", assertThrows(NativeApiException::class.java) {
            NativeAccountApi(session).favorite(testAnime(), false, "owner")
        }.code)
    }

    @Test fun staleOrDifferentOwnerCannotSendFavoriteMutation() {
        var posts = 0
        val session = OfficialHttpSession(openConnection = { uri ->
            if (uri.path.startsWith("/ajax/")) posts++
            Response(uri, profile("other"))
        })
        val anime = NativeAccountApi.parseFavorites(InertHtml.parse(card("1"))).single()
        assertThrows(NativeApiException::class.java) { NativeAccountApi(session).favorite(anime, true, "owner") }
        assertEquals(0, posts)
    }

    @Test fun redirectsSizesAndUntrustedOriginsFailClosed() {
        var connections = 0
        val session = OfficialHttpSession(openConnection = { uri ->
            connections++
            Response(uri, "private", 302)
        })
        assertThrows(Exception::class.java) { session.get("https://evil.invalid/") }
        assertEquals(0, connections)
        assertThrows(NativeApiException::class.java) { session.get("https://ani.gamer.com.tw/") }
        for (declared in listOf(-1, 2_000_001)) {
            val limited = OfficialHttpSession(openConnection = { uri ->
                Response(uri, "x".repeat(2_000_001), length = declared)
            })
            assertEquals("response-too-large", assertThrows(NativeApiException::class.java) {
                limited.get("https://ani.gamer.com.tw/")
            }.code)
        }
    }

    @Test fun sessionRetirementPreventsFurtherNetworkWorkAndCookieRotationStaysLocal() {
        var current = true
        val requests = mutableListOf<Response>()
        val session = OfficialHttpSession(mapOf("ani.gamer.com.tw" to "session=old"),
            isCurrent = { current }, openConnection = { uri ->
                Response(uri, "ok", headers = mapOf("Set-Cookie" to listOf(
                    "session=new; Domain=gamer.com.tw; Path=/; Secure"))).also { requests += it }
            })
        session.get("https://ani.gamer.com.tw/")
        session.get("https://ani.gamer.com.tw/")
        assertEquals("session=old", requests[0].getRequestProperty("Cookie"))
        assertEquals("session=new", requests[1].getRequestProperty("Cookie"))
        current = false
        assertThrows(NativeApiException::class.java) { session.get("https://ani.gamer.com.tw/") }
        assertEquals(2, requests.size)
    }

    @Test fun guestLoginRedirectIsRecognizedWithoutFollowingIt() {
        var response: Response? = null
        val session = OfficialHttpSession(openConnection = { uri ->
            Response(uri, "", 302, mapOf("Location" to listOf(
                "https://user.gamer.com.tw/login.php?redirect=private"))).also { response = it }
        })
        assertNull(NativeAccountApi(session).account())
        assertFalse(response!!.instanceFollowRedirects)
        assertTrue(response!!.disconnected)
    }

    @Test fun arbitraryRedirectCannotMasqueradeAsAGuestLogin() {
        for (url in listOf("http://user.gamer.com.tw/login.php", "https://evil.invalid/login.php",
            "https://user.gamer.com.tw:444/login.php", "https://user.gamer.com.tw/other.php")) {
            val session = OfficialHttpSession(openConnection = { uri ->
                Response(uri, "", 302, mapOf("Location" to listOf(url)))
            })
            assertEquals("redirect-rejected", assertThrows(NativeApiException::class.java) {
                NativeAccountApi(session).account()
            }.code)
        }
    }

    private fun profile(id: String) = "<a class='user-info'><span class='user-id'>$id</span>" +
        "<b class='user-name'>名字</b></a>"
    private fun collectionPage(id: String) = "<h2 class='theme-title'>我的動畫</h2>" +
        "<a href='viewList.php?u=$id'>觀看紀錄</a>"
    private fun testAnime() = NativeAccountApi.parseFavorites(InertHtml.parse(card("1"))).single()
    private fun control(id: String, selected: Boolean, modern: Boolean = false): String {
        val active = if (modern) "btn-is-active" else "ordered"
        return "<button class='${if (selected) active else ""}' " +
            "onclick=\"toggleGather(10, $id, this${if (modern) ", 'btn-is-active'" else ""})\"></button>"
    }
    private fun card(id: String) = "<a class='theme-list-main' href='/animeRef.php?sn=$id'>" +
        "<div class='theme-name'>動畫$id</div><img class='theme-img' src='https://p2.bahamut.com.tw/$id.jpg'>" +
        "<button onclick='toggleGather(10, $id, this)'></button></a>"
}
