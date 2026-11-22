package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The public concert list, in the plain-text format `ExtraVideoCatalog` parses. */
const val CONCERTS_MANIFEST_PATH = "/v1/concerts/manifest.txt"

/** Prefix every concert playlist and segment is served under. */
const val CONCERTS_RELAY_PREFIX = "/v1/concerts/relay"

/** One listed concert: where it is served from, and what to call it. */
data class Concert(val sourceUrl: String, val title: String)

/**
 * The concerts anyone may watch without signing in, read from the manifest at
 * `CONCERTS_MANIFEST_URL`.
 *
 * The list stays on the server: the apps are given [ConcertRelay] URLs, never
 * the source URLs. That keeps the manifest address out of the binaries and the
 * repository, and lets the list change without a release.
 *
 * A failed refresh keeps serving the last good list, so a manifest host outage
 * does not empty the gallery.
 */
class ConcertCatalog(
    private val httpClient: HttpClient,
    private val manifestUrl: String,
    private val ttl: Duration = 5.minutes,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: List<Concert>? = null
    private var fetchedAtMs = 0L

    val isConfigured: Boolean get() = manifestUrl.isNotBlank()

    /** The current list, or null when there has never been a readable one. */
    suspend fun concerts(): List<Concert>? = mutex.withLock {
        val current = cached
        if (current != null && clock() - fetchedAtMs < ttl.inWholeMilliseconds) return current
        val fresh = fetch() ?: return current
        cached = fresh
        fetchedAtMs = clock()
        fresh
    }

    private suspend fun fetch(): List<Concert>? {
        if (!isConfigured) return null
        val response = runCatching { httpClient.get(manifestUrl) }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return parse(response.bodyAsText())
    }

    companion object {
        /** One concert per line, URL first with an optional title; `#` lines are comments. */
        fun parse(text: String): List<Concert> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val url = line.substringBefore(' ')
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                Concert(sourceUrl = url, title = line.substringAfter(' ', "").trim())
            }
            .toList()
    }
}

/**
 * Which relay paths map to which source, derived from the concert list alone.
 *
 * A numbered stream's folder (`live/event15`) admits its sibling renditions
 * (`live/event15_360p`, …), because the apps build the adaptive ladder and the
 * caption audio track from those. Any other listed playlist admits only its own
 * folder. Nothing outside the listed folders is fetched.
 */
internal class RelayRoutes(concerts: List<Concert>) {

    private class Route(val origin: String, val folder: Regex, val relayPath: String)

    private val routes: List<Route> = concerts.mapNotNull(::routeFor)
        .distinctBy { it.folder.pattern }

    /** The relay URL the apps are given for [concert], or null when it cannot be relayed. */
    fun relayUrl(concert: Concert, base: String): String? {
        val route = routeFor(concert) ?: return null
        if (routes.none { it.folder.pattern == route.folder.pattern && it.origin == route.origin }) return null
        return "$base$CONCERTS_RELAY_PREFIX/${route.relayPath}"
    }

    /** The source URL behind a relay request, or null when the path is not one the list admits. */
    fun sourceUrl(segments: List<String>, query: String): String? {
        if (segments.size < 2 || segments.any { !SAFE_SEGMENT.matches(it) || it == "." || it == ".." }) return null
        if (!SAFE_QUERY.matches(query)) return null
        val folder = segments.dropLast(1).joinToString("/")
        val route = routes.firstOrNull { it.folder.matches(folder) } ?: return null
        val path = segments.joinToString("/")
        return "${route.origin}/$path" + if (query.isEmpty()) "" else "?$query"
    }

    /** Every source origin, so a playlist that names one absolutely can be rewritten. */
    val origins: Set<String> get() = routes.mapTo(mutableSetOf()) { it.origin }

    private fun routeFor(concert: Concert): Route? {
        val uri = runCatching { URI(concert.sourceUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val authority = uri.rawAuthority ?: return null
        val path = uri.rawPath?.removePrefix("/")?.takeIf { it.contains('/') } ?: return null
        val folder = path.substringBeforeLast('/')
        val folderPattern = NUMBERED_FOLDER.matchEntire(folder)
            ?.let { Regex(Regex.escape(folder) + "(_[A-Za-z0-9]+)?") }
            ?: Regex(Regex.escape(folder))
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return Route(origin = "$scheme://$authority", folder = folderPattern, relayPath = path + query)
    }

    private companion object {
        val NUMBERED_FOLDER = Regex("""live/event\d+""")
        val SAFE_SEGMENT = Regex("""[A-Za-z0-9._-]+""")
        val SAFE_QUERY = Regex("""[A-Za-z0-9=&_.-]*""")
    }
}

/**
 * Serves the concert list and relays the listed streams, so the apps only ever
 * talk to this service when playing a concert.
 *
 * Only the headers a player needs are passed back, and a playlist that names
 * its source absolutely is rewritten to point here.
 */
class ConcertRelay(
    private val httpClient: HttpClient,
    private val catalog: ConcertCatalog,
) {

    suspend fun respondManifest(call: ApplicationCall) {
        if (!catalog.isConfigured) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Concerts are not available right now."))
            return
        }
        val concerts = catalog.concerts() ?: run {
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("Could not load the concert list right now."))
            return
        }
        val routes = RelayRoutes(concerts)
        val base = call.publicBase()
        val body = concerts.mapNotNull { concert ->
            routes.relayUrl(concert, base)?.let { url ->
                if (concert.title.isEmpty()) url else "$url ${concert.title}"
            }
        }.joinToString("\n", postfix = "\n")
        call.allowAnyOrigin()
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondText(body, ContentType.Text.Plain)
    }

    suspend fun relay(call: ApplicationCall, segments: List<String>) {
        // On errors too, so the web player sees the real status rather than a
        // network failure the browser substitutes for an unreadable response.
        call.allowAnyOrigin()
        val routes = RelayRoutes(catalog.concerts().orEmpty())
        val source = routes.sourceUrl(segments, call.request.queryString())
        if (source == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val range = call.request.header(HttpHeaders.Range)
        val failed = runCatching {
            httpClient.prepareGet(source) {
                range?.let { header(HttpHeaders.Range, it) }
            }.execute { upstream ->
                if (!upstream.status.isSuccess()) {
                    call.respond(
                        if (upstream.status == HttpStatusCode.NotFound) HttpStatusCode.NotFound
                        else HttpStatusCode.BadGateway,
                    )
                    return@execute
                }
                PASSED_HEADERS.forEach { name ->
                    upstream.headers[name]?.let { call.response.header(name, it) }
                }
                val type = upstream.contentType()
                if (segments.last().endsWith(".m3u8")) {
                    var text = upstream.bodyAsText()
                    val relayBase = call.publicBase() + CONCERTS_RELAY_PREFIX
                    routes.origins.forEach { origin -> text = text.replace("$origin/", "$relayBase/") }
                    call.respondText(text, type ?: MPEGURL, upstream.status)
                } else {
                    // Buffered rather than streamed: a segment is a few seconds
                    // of video, a few hundred KB, and the upstream response is
                    // released when this block returns.
                    call.respondBytes(upstream.readRawBytes(), type, upstream.status)
                }
            }
        }.isFailure
        if (failed && !call.response.isCommitted) call.respond(HttpStatusCode.BadGateway)
    }

    private companion object {
        val MPEGURL = ContentType("application", "vnd.apple.mpegurl")
        val PASSED_HEADERS = listOf(HttpHeaders.CacheControl, HttpHeaders.ContentRange, HttpHeaders.AcceptRanges)
    }
}

/**
 * Lets the web build read a concert response cross-origin. Skipped when the
 * CORS plugin has already answered for an allowed origin, because a second
 * `Access-Control-Allow-Origin` header makes the browser reject both.
 */
private fun ApplicationCall.allowAnyOrigin() {
    if (response.headers[HttpHeaders.AccessControlAllowOrigin] == null) {
        response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    }
}

/**
 * This service's own scheme and authority as the caller reached it. Behind Fly's
 * proxy the connection arrives as plain HTTP, so the forwarded scheme is used.
 */
internal fun ApplicationCall.publicBase(): String {
    val scheme = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.local.scheme
    val host = request.headers[HttpHeaders.Host] ?: request.local.serverHost
    return "$scheme://$host"
}
