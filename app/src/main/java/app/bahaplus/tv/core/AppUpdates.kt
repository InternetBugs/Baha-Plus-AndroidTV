package app.bahaplus.tv.core

import org.json.JSONObject

/** SemVer precedence, including prereleases; build metadata does not affect ordering. */
class ReleaseVersion private constructor(
    private val numbers: List<String>,
    private val prerelease: List<String>
) : Comparable<ReleaseVersion> {
    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            compareNumber(left, right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumeric = left.all { it in '0'..'9' }
            val rightNumeric = right.all { it in '0'..'9' }
            val comparison = when {
                leftNumeric && rightNumeric -> compareNumber(left, right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex(
            "^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
        )

        fun parse(value: String): ReleaseVersion {
            require(value.length <= 100) { "Version is too long" }
            val match = requireNotNull(pattern.matchEntire(value)) { "Invalid release version" }
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            require(prerelease.none { part ->
                part.length > 1 && part[0] == '0' && part.all { it in '0'..'9' }
            }) { "Invalid prerelease number" }
            return ReleaseVersion(match.groupValues.subList(1, 4), prerelease)
        }

        private fun compareNumber(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}

data class AppRelease(val versionName: String, val pageUrl: String, val notes: String)

sealed interface UpdateCheckResult {
    data class Available(val release: AppRelease) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

object AppUpdates {
    const val repository = "InternetBugs/Baha-Plus-AndroidTV"
    const val latestReleaseApi = "https://api.github.com/repos/$repository/releases/latest"

    fun evaluate(currentVersion: String, release: JSONObject): UpdateCheckResult {
        val current = ReleaseVersion.parse(currentVersion)
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) return UpdateCheckResult.UpToDate
        val tag = release.getString("tag_name")
        val latest = ReleaseVersion.parse(tag)
        if (latest.isPrerelease || latest <= current) return UpdateCheckResult.UpToDate
        return UpdateCheckResult.Available(AppRelease(
            versionName = tag.removePrefix("v"),
            pageUrl = "https://github.com/$repository/releases/tag/$tag",
            notes = if (release.isNull("body")) "" else release.optString("body").trim().take(12_000)
        ))
    }
}
