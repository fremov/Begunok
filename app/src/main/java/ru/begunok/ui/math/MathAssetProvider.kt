package ru.begunok.ui.math

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.IOException

/** A closed virtual origin: no request ever falls through to WebView's network stack. */
internal class MathAssetProvider(
    private val assets: AssetManager,
    lessonIds: Set<String>,
) {
    private val lessonPaths = lessonIds.filter { LESSON_ID.matches(it) }
        .associateBy { "${PREFIX}lessons/$it.html" }

    fun lessonUrl(id: String, isDark: Boolean): Uri {
        require(id in lessonPaths.values)
        return Uri.parse("https://$HOST${PREFIX}lessons/$id.html?theme=${if (isDark) "dark" else "light"}")
    }

    fun lessonId(uri: Uri): String? = if (trusted(uri) && validLessonQuery(uri)) {
        lessonPaths[uri.encodedPath]
    } else null

    fun intercept(uri: Uri, method: String, mainFrame: Boolean, selectedId: String): WebResourceResponse {
        if (method != "GET" || !trusted(uri)) return denied()
        val path = uri.encodedPath ?: return denied()
        val lesson = lessonId(uri)
        val mime = when {
            lesson != null -> "text/html"
            mainFrame -> return denied()
            uri.encodedQuery != null || uri.encodedFragment != null -> return denied()
            path == "${PREFIX}lesson.css" -> "text/css"
            path == "${PREFIX}lesson.js" -> "text/javascript"
            path == "${PREFIX}favicon.svg" -> "image/svg+xml"
            path.startsWith("${PREFIX}media/") -> {
                val filename = path.removePrefix("${PREFIX}media/")
                if (!MEDIA_NAME.matches(filename)) return denied()
                IMAGE_TYPES[filename.substringAfterLast('.')] ?: return denied()
            }
            else -> return denied()
        }
        if (mainFrame && lesson != selectedId) return denied()
        return try {
            WebResourceResponse(
                mime,
                if (mime in TEXT_TYPES || mime == "image/svg+xml") "UTF-8" else null,
                200,
                "OK",
                RESPONSE_HEADERS,
                assets.open("math/" + path.removePrefix(PREFIX), AssetManager.ACCESS_STREAMING),
            )
        } catch (_: IOException) {
            response(404, "Not Found")
        }
    }

    private fun trusted(uri: Uri): Boolean = uri.isHierarchical && uri.scheme == "https" &&
        uri.encodedAuthority == HOST && uri.userInfo == null && uri.port == -1 &&
        uri.encodedPath?.let { it.startsWith(PREFIX) && '%' !in it && '\\' !in it } == true

    private fun validLessonQuery(uri: Uri): Boolean = uri.encodedQuery == null ||
        uri.encodedQuery == "theme=dark" || uri.encodedQuery == "theme=light"

    private fun denied() = response(403, "Forbidden")

    private fun response(status: Int, reason: String) = WebResourceResponse(
        "text/plain", "UTF-8", status, reason, RESPONSE_HEADERS, ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val PREFIX = "/assets/math/"
        val LESSON_ID = Regex("[a-z0-9_-]+")
        val MEDIA_NAME = Regex("[a-f0-9]{8,64}\\.(png|jpg|jpeg|gif|webp|svg|avif|bmp|ico)")
        val TEXT_TYPES = setOf("text/html", "text/css", "text/javascript")
        val IMAGE_TYPES = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
            "avif" to "image/avif", "bmp" to "image/bmp", "ico" to "image/x-icon",
        )
        val RESPONSE_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; " +
                "frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; " +
                "worker-src 'none'",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Cross-Origin-Resource-Policy" to "same-origin",
            "Cache-Control" to "no-store",
        )
    }
}
