package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.livingpresence.mediakit.CachedManifest
import com.livingpresence.mediakit.ManifestStore
import platform.Foundation.NSUserDefaults

/**
 * Caches the extras manifest in `NSUserDefaults`.
 *
 * Unlike the Discord refresh token — which earns the Keychain — this is a list
 * of URLs the app fetches in the clear anyway, so the ordinary defaults store is
 * the right home for it.
 */
private object IosManifestStore : ManifestStore {

    private val defaults get() = NSUserDefaults.standardUserDefaults

    override fun read(): CachedManifest? = cachedManifestOf(
        body = defaults.stringForKey(ManifestStoreKeys.BODY),
        fetchedAtEpochMs = defaults.doubleForKey(ManifestStoreKeys.FETCHED_AT).toLong(),
    )

    override fun write(manifest: CachedManifest) {
        defaults.setObject(manifest.body, ManifestStoreKeys.BODY)
        // NSUserDefaults has no Long accessor; epoch millis fit a Double exactly
        // (well under 2^53) so the round trip is lossless.
        defaults.setDouble(manifest.fetchedAtEpochMs.toDouble(), ManifestStoreKeys.FETCHED_AT)
    }
}

@Composable
actual fun rememberManifestStore(): ManifestStore = remember { IosManifestStore }
