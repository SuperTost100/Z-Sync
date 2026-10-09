package de.kjell.zencompanion

import android.content.Context
import android.content.ContextWrapper
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.SyncCrypto
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.SyncHttpRequest
import de.kjell.zencompanion.sync.SyncHttpResponse
import de.kjell.zencompanion.sync.SyncHttpTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Expired credentials and sign-out races, driven through the same transport
 * seams `AccountStore.connect` uses in production. Mirrors the iOS cases in
 * `SyncTransportTests`.
 */
class AccountSessionTests {
    private val context: Context = ContextWrapper(null)
    private val kB = ByteArray(32) { 0xAB.toByte() }
    private val storageKeys = SyncCrypto.KeyBundle(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 })
    private val account = AccountStore.AccountSnapshot(
        email = "a@b.c",
        uid = "u1",
        sessionTokenHex = "01".repeat(32),
        kBHex = "ab".repeat(32),
    )
    private lateinit var server: FxAAndSyncServer
    private var previousAuth: SyncHttpTransport? = null
    private var previousSync: SyncHttpTransport? = null

    @Before
    fun setUp() {
        server = FxAAndSyncServer(kB, storageKeys)
        AccountStore.resetForTests()
        val store = MemoryPrefsStore()
        AccountStore.secureStoreFactory = { store }
        AccountStore.legacyStoreFactory = { NoLegacyStore }
        previousAuth = AccountStore.authTransport
        previousSync = AccountStore.syncTransport
        AccountStore.authTransport = server
        AccountStore.syncTransport = server
        AccountStore.save(context, account)
    }

    @After
    fun tearDown() {
        previousAuth?.let { AccountStore.authTransport = it }
        previousSync?.let { AccountStore.syncTransport = it }
        AccountStore.resetForTests()
        AccountStore.restoreDefaultFactoriesForTests()
    }

    @Test
    fun storage401RefreshesCredentialsAndRetriesOnce() {
        server.storage401Remaining = 1

        runBlocking { SpacesSyncService.loadSnapshot(context) }

        assertEquals("the 401 must force a fresh token-server exchange", 2, server.tokenServerCalls)
        assertEquals(0, server.storage401Remaining)
    }

    @Test
    fun persistentStorage401SurfacesAfterOneRetry() {
        server.storage401Remaining = 10

        try {
            runBlocking { SpacesSyncService.loadSnapshot(context) }
            fail("expected SyncError.Unauthorized")
        } catch (expected: SyncError.Unauthorized) {
            // Expected: one retry, then the error surfaces.
        }
        assertEquals(2, server.tokenServerCalls)
    }

    @Test
    fun signOutDuringCredentialFetchDropsTheResult() {
        server.onTokenServer = { AccountStore.clear(context) }

        try {
            runBlocking { AccountStore.connect(context) }
            fail("expected SyncError.NotSignedIn")
        } catch (expected: SyncError.NotSignedIn) {
            // Expected: the credentials fetched for the old session are dropped.
        }

        server.onTokenServer = null
        AccountStore.save(context, account)
        runBlocking { AccountStore.connect(context) }
        assertEquals("the dropped credentials must not be reused", 2, server.tokenServerCalls)
    }

    private class MemoryPrefsStore : AccountStore.AccountPrefsStore {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String): Boolean {
            values[key] = value
            return true
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private object NoLegacyStore : AccountStore.LegacyPlainStore {
        override fun read(key: String): String? = null
        override fun delete() {}
    }
}

/**
 * Answers the FxA OAuth, scoped-key and token-server calls plus the Sync
 * storage reads one `connect()` + snapshot load makes, routed by URL.
 */
private class FxAAndSyncServer(
    private val kB: ByteArray,
    private val storageKeys: SyncCrypto.KeyBundle,
) : SyncHttpTransport {
    @Volatile var tokenServerCalls = 0
        private set

    @Volatile var storage401Remaining = 0

    var onTokenServer: (() -> Unit)? = null

    @Synchronized
    override fun execute(request: SyncHttpRequest): SyncHttpResponse {
        val host = request.url.host
        val path = request.url.path
        fun json(body: Any, status: Int = 200) =
            SyncHttpResponse(status, mapOf("x-last-modified" to "1.00"), body.toString().toByteArray(Charsets.UTF_8))
        return when {
            host == "oauth.accounts.firefox.com" || path.endsWith("/oauth/token") ->
                json(JSONObject().put("access_token", "access"))
            path.endsWith("/account/scoped-key-data") ->
                json(
                    JSONObject().put(
                        "https://identity.mozilla.com/apps/oldsync",
                        JSONObject().put("keyRotationTimestamp", 0),
                    ),
                )
            host == "token.services.mozilla.com" -> {
                tokenServerCalls++
                onTokenServer?.invoke()
                json(
                    JSONObject()
                        .put("uid", "1")
                        .put("api_endpoint", "https://sync.example.com/1.0/sync/1.5")
                        .put("id", "hawk-$tokenServerCalls")
                        .put("key", "key-$tokenServerCalls")
                        .put("duration", 3600),
                )
            }
            host != "sync.example.com" -> SyncHttpResponse(500, emptyMap(), ByteArray(0))
            path.endsWith("/storage/crypto/keys") -> {
                val keys = JSONObject()
                    .put(
                        "default",
                        JSONArray()
                            .put(Base64.getEncoder().encodeToString(storageKeys.encryptionKey))
                            .put(Base64.getEncoder().encodeToString(storageKeys.hmacKey)),
                    )
                    .put("collections", JSONObject())
                val payload = SyncCrypto.encryptBSO(
                    keys.toString().toByteArray(Charsets.UTF_8),
                    SyncCrypto.syncKeyBundle(kB),
                )
                json(JSONObject().put("id", "keys").put("payload", payload))
            }
            path.endsWith("/storage/spaces") && storage401Remaining > 0 -> {
                storage401Remaining--
                SyncHttpResponse(401, emptyMap(), ByteArray(0))
            }
            else -> json(JSONArray())
        }
    }
}
