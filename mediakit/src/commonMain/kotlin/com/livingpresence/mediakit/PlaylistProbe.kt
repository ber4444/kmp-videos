package com.livingpresence.mediakit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * What a probe of one master playlist established.
 *
 * [Missing] is a deliberate 4xx: the stream genuinely is not there, so callers
 * drop it rather than retrying. [Transient] covers everything that might work on
 * the next attempt — transport failures, 5xx, unreadable or unparseable bodies.
 */
internal sealed interface ProbeOutcome {
    data class Found(val isLive: Boolean, val durationMs: Long) : ProbeOutcome
    data object Missing : ProbeOutcome
    data object Transient : ProbeOutcome
}

/**
 * Fetches a master playlist, follows its first non-I-frame variant, and reads
 * live/duration facts off the resulting media playlist.
 *
 * Shared by [EventCatalog] (numbered events, URL built from [MediaKitConfig])
 * and [ExtraVideoCatalog] (arbitrary URLs from the remote manifest) — the two
 * differ only in where the URL comes from, so the HTTP walk lives here once.
 */
internal class PlaylistProbe(private val httpClient: HttpClient) {

    /**
     * Probe [masterUrl] once, retrying only [ProbeOutcome.Transient] outcomes up
     * to [maxAttempts] times with [backoff] between tries.
     */
    suspend fun probeWithRetry(
        masterUrl: String,
        maxAttempts: Int,
        backoff: Duration,
    ): ProbeOutcome {
        var last: ProbeOutcome = ProbeOutcome.Transient
        repeat(maxAttempts) { attempt ->
            last = probe(masterUrl)
            when (last) {
                is ProbeOutcome.Found, ProbeOutcome.Missing -> return last
                ProbeOutcome.Transient ->
                    if (attempt < maxAttempts - 1 && backoff > Duration.ZERO) delay(backoff)
            }
        }
        return last
    }

    /** Single attempt: master → variant → media playlist. */
    suspend fun probe(masterUrl: String): ProbeOutcome {
        println("probe: fetching $masterUrl")
        val response: HttpResponse = runCatching { httpClient.get(masterUrl) }
            .onFailure { println("probe: master request failed: $it") }
            .getOrElse { return ProbeOutcome.Transient }

        // 4xx (incl. 404) = the stream genuinely doesn't exist → no retry.
        // 5xx = server-side hiccup → transient, worth a retry.
        if (!response.status.isSuccess()) {
            return if (response.status.value in 400..499) ProbeOutcome.Missing else ProbeOutcome.Transient
        }

        val masterText = runCatching { response.bodyAsText() }
            .onFailure { println("probe: master bodyAsText failed: $it") }
            .getOrElse { return ProbeOutcome.Transient }

        val variants = runCatching { PlaylistInspector.parseMaster(masterText) }
            .onFailure { println("probe: parseMaster failed: $it") }
            .getOrElse { return ProbeOutcome.Transient }

        val primary = variants.firstOrNull { !it.isIFrameOnly } ?: run {
            println("probe: no primary variant found in $variants")
            return ProbeOutcome.Transient
        }
        val chunklistUrl = resolveUri(masterUrl, primary.uri)
        println("probe: resolved chunklist URL: $chunklistUrl")

        val chunklistResponse: HttpResponse = runCatching { httpClient.get(chunklistUrl) }
            .onFailure { println("probe: chunklist request failed: $it") }
            .getOrElse { return ProbeOutcome.Transient }

        if (!chunklistResponse.status.isSuccess()) {
            println("probe: chunklist response not success: ${chunklistResponse.status}")
            return ProbeOutcome.Transient
        }

        val chunklistText = runCatching { chunklistResponse.bodyAsText() }
            .onFailure { println("probe: chunklist bodyAsText failed: $it") }
            .getOrElse { return ProbeOutcome.Transient }

        val media = runCatching { PlaylistInspector.parseMediaPlaylist(chunklistText) }
            .onFailure { println("probe: parseMediaPlaylist failed: $it") }
            .getOrElse { return ProbeOutcome.Transient }

        println("probe: success! isLive=${media.isLive}")
        return ProbeOutcome.Found(
            isLive = media.isLive,
            durationMs = (media.durationSeconds * 1000).toLong(),
        )
    }

    /** Resolves a playlist-relative [reference] against the [base] playlist URL. */
    private fun resolveUri(base: String, reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        val baseDir = base.substringBeforeLast('/', "")
        return "$baseDir/$reference"
    }
}
