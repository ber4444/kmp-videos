package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.livingpresence.mediakit.CachedManifest
import com.livingpresence.mediakit.ManifestStore
import kotlinx.browser.window

/**
 * Caches the extras manifest in `localStorage`, which survives the tab.
 *
 * Every access is guarded: `localStorage` throws outright in some privacy modes,
 * and the only consequence of a miss is re-fetching the manifest.
 */
private object WasmManifestStore : ManifestStore {

    private const val BODY_KEY = "ics.${ManifestStoreKeys.STORE}.${ManifestStoreKeys.BODY}"
    private const val FETCHED_AT_KEY = "ics.${ManifestStoreKeys.STORE}.${ManifestStoreKeys.FETCHED_AT}"

    override fun read(): CachedManifest? = cachedManifestOf(
        body = runCatching { window.localStorage.getItem(BODY_KEY) }.getOrNull(),
        fetchedAtEpochMs = runCatching { window.localStorage.getItem(FETCHED_AT_KEY) }
            .getOrNull()
            ?.toLongOrNull(),
    )

    override fun write(manifest: CachedManifest) {
        runCatching {
            window.localStorage.setItem(BODY_KEY, manifest.body)
            window.localStorage.setItem(FETCHED_AT_KEY, manifest.fetchedAtEpochMs.toString())
        }
    }
}

@Composable
actual fun rememberManifestStore(): ManifestStore = remember { WasmManifestStore }
