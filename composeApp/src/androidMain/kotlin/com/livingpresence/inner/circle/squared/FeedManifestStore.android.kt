package com.livingpresence.inner.circle.squared

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.livingpresence.mediakit.CachedManifest
import com.livingpresence.mediakit.ManifestStore

/** Caches the extras manifest in app-private `SharedPreferences`. */
private class AndroidManifestStore(context: Context) : ManifestStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(ManifestStoreKeys.STORE, Context.MODE_PRIVATE)

    override fun read(): CachedManifest? = cachedManifestOf(
        body = prefs.getString(ManifestStoreKeys.BODY, null),
        fetchedAtEpochMs = prefs.getLong(ManifestStoreKeys.FETCHED_AT, 0L),
    )

    override fun write(manifest: CachedManifest) {
        prefs.edit()
            .putString(ManifestStoreKeys.BODY, manifest.body)
            .putLong(ManifestStoreKeys.FETCHED_AT, manifest.fetchedAtEpochMs)
            .apply()
    }
}

@Composable
actual fun rememberManifestStore(): ManifestStore {
    val context = LocalContext.current
    return remember(context) { AndroidManifestStore(context) }
}
