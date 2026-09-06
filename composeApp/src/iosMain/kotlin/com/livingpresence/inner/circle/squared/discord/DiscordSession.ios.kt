@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.livingpresence.inner.circle.squared.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val SERVICE = "com.livingpresence.inner.circle.squared.discord"
private const val REFRESH_TOKEN_ACCOUNT = "refresh_token"
private const val DISPLAY_NAME_ACCOUNT = "display_name"

/**
 * Marks that this *install* has run before. Deliberately in `NSUserDefaults`,
 * which is the one thing here that does not outlive a delete — see
 * [purgeKeychainOnFirstRun].
 */
private const val INSTALL_MARKER_KEY = "ics.discord.installMarker"

/**
 * Stores the session in the iOS Keychain.
 *
 * `NSUserDefaults` would be a few lines shorter and wrong: it lands in a plist
 * inside the app container that shows up in unencrypted backups. The Keychain is
 * what the platform provides for bearer credentials.
 *
 * Items use `kSecAttrAccessibleAfterFirstUnlock` so a session survives a reboot
 * and can still be read in the background, without being readable while the
 * device has never been unlocked.
 */
private object IosDiscordSessionStore : DiscordSessionStore {

    init {
        purgeKeychainOnFirstRun()
    }

    override fun save(session: DiscordSession) {
        keychainSet(REFRESH_TOKEN_ACCOUNT, session.refreshToken)
        keychainSet(DISPLAY_NAME_ACCOUNT, session.displayName)
    }

    override fun load(): DiscordSession? {
        val refreshToken = keychainGet(REFRESH_TOKEN_ACCOUNT)?.takeIf { it.isNotBlank() }
            ?: return null
        return DiscordSession(
            refreshToken = refreshToken,
            displayName = keychainGet(DISPLAY_NAME_ACCOUNT).orEmpty(),
        )
    }

    override fun clear() {
        keychainSet(REFRESH_TOKEN_ACCOUNT, null)
        keychainSet(DISPLAY_NAME_ACCOUNT, null)
    }
}

/**
 * Drops any Keychain session left behind by a previous install of this app.
 *
 * iOS does not delete Keychain items when an app is uninstalled — they are the
 * OS's, keyed by the bundle seed, not the app container's. So a reinstall would
 * otherwise find the old refresh token, restore the session, and put the user
 * straight into the gallery without a sign-in they never performed. Two reasons
 * that is wrong rather than convenient: deleting the app is how a user expects to
 * revoke a credential, and Discord refresh tokens do not expire on their own, so
 * the one they thought they removed stays live for the next install of the same
 * bundle id — the previous owner of a handed-on device included.
 *
 * `NSUserDefaults` is the marker precisely because it has the opposite lifetime:
 * it lives in the app container and *is* wiped on uninstall, so "no marker" means
 * "this install has never run", which is exactly the moment to clear.
 *
 * Ordering matters — this runs from [IosDiscordSessionStore]'s initializer, so it
 * has already happened by the time `DiscordConnectionViewModel` calls `load()`.
 */
private fun purgeKeychainOnFirstRun() {
    val defaults = NSUserDefaults.standardUserDefaults
    if (defaults.boolForKey(INSTALL_MARKER_KEY)) {
        return
    }
    keychainSet(REFRESH_TOKEN_ACCOUNT, null)
    keychainSet(DISPLAY_NAME_ACCOUNT, null)
    defaults.setBool(true, INSTALL_MARKER_KEY)
}

/** Builds a `CFDictionary` for a Keychain call, plus any [extra] attributes. */
private fun secQuery(account: String, extra: List<Pair<CFTypeRef?, CFTypeRef?>>): CFMutableDictionaryRef {
    val query = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        0,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )!!
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    val service = CFBridgingRetain(SERVICE as NSString)
    val accountRef = CFBridgingRetain(account as NSString)
    CFDictionaryAddValue(query, kSecAttrService, service)
    CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
    extra.forEach { (key, value) -> CFDictionaryAddValue(query, key, value) }
    CFRelease(service)
    CFRelease(accountRef)
    return query
}

/** Writes [value] for [account], or deletes the item when [value] is null. */
private fun keychainSet(account: String, value: String?) {
    // Keychain has no upsert: delete first, then add, so a re-login replaces the
    // previous token instead of failing with errSecDuplicateItem.
    val deleteQuery = secQuery(account, emptyList())
    SecItemDelete(deleteQuery)
    CFRelease(deleteQuery)
    if (value == null) {
        return
    }
    val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
    val dataRef = CFBridgingRetain(data)
    val addQuery = secQuery(
        account,
        listOf(
            kSecValueData to dataRef,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
        ),
    )
    SecItemAdd(addQuery, null)
    CFRelease(addQuery)
    CFRelease(dataRef)
}

/** Reads the string stored for [account], or null when absent/unreadable. */
private fun keychainGet(account: String): String? = memScoped {
    val query = secQuery(
        account,
        listOf(
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ),
    )
    val result = alloc<CFTypeRefVar>()
    val status = SecItemCopyMatching(query, result.ptr as CValuesRef<CFTypeRefVar>)
    CFRelease(query)
    if (status != errSecSuccess) {
        return@memScoped null
    }
    val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
    NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
}

@Composable
actual fun rememberDiscordSessionStore(): DiscordSessionStore = remember { IosDiscordSessionStore }
