package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import com.livingpresence.mediakit.CachedManifest
import com.livingpresence.mediakit.ManifestStore

/**
 * The platform's persistent [ManifestStore], backing the extras manifest's
 * day-long TTL.
 *
 * The TTL only buys anything if the cached body outlives the process — an app
 * session almost never lasts a day — so each platform stores it in its ordinary
 * app-private key-value storage: `SharedPreferences`, `NSUserDefaults`,
 * `localStorage`. Nothing here is a secret; the manifest is a list of URLs the
 * app is about to request anyway.
 *
 * Reads and writes must never throw: storage can be unavailable (a browser in
 * private mode), and the only cost of a miss is one extra fetch.
 */
@Composable
expect fun rememberManifestStore(): ManifestStore

/** Shared key names, so the three platform stores stay in step. */
internal object ManifestStoreKeys {
    const val STORE = "extra_videos"
    const val BODY = "manifest_body"
    const val FETCHED_AT = "manifest_fetched_at"
}

/** Builds a [CachedManifest] from two raw values, or null when either is absent. */
internal fun cachedManifestOf(body: String?, fetchedAtEpochMs: Long?): CachedManifest? {
    if (body == null || fetchedAtEpochMs == null || fetchedAtEpochMs <= 0L) return null
    return CachedManifest(body = body, fetchedAtEpochMs = fetchedAtEpochMs)
}
