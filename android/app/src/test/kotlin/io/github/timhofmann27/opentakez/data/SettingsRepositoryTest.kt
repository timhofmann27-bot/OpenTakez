package io.github.timhofmann27.opentakez.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.ServerConfig
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reversible and recognisable; not secure, only for tests. Counts calls to prove it is used. */
class FakeTokenCipher : TokenCipher {
    override fun encrypt(plain: String): String =
        Base64.getEncoder().encodeToString("fake:${plain.reversed()}".toByteArray())

    override fun decrypt(encoded: String): String {
        val text = String(Base64.getDecoder().decode(encoded))
        require(text.startsWith("fake:")) { "bad ciphertext" }
        return text.removePrefix("fake:").reversed()
    }
}

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(scope: TestScope, cipher: TokenCipher = FakeTokenCipher(), file: File = File(tmp.root, "s.preferences_pb")) =
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope.backgroundScope, produceFile = { file }),
            cipher,
        )

    @Test
    fun `defaults`() = runTest {
        val settings = repo(this)

        assertEquals("", settings.serverUrl.first())
        assertEquals("", settings.token.first())
        assertEquals(Engine.DEVICE, settings.defaultEngine.first())
        assertEquals("", settings.language.first())
        assertNull(settings.lastYtDlpCheck.first())
        assertFalse(settings.firstRunDone.first())
        assertNull(settings.serverConfig())
    }

    @Test
    fun `values round trip`() = runTest {
        val settings = repo(this)

        settings.setServerUrl("  https://dl.example.com  ")
        settings.setToken("s3cret-token")
        settings.setDefaultEngine(Engine.SERVER)
        settings.setLanguage("de")
        settings.setLastYtDlpCheck(1_700_000_000_000)
        settings.setFirstRunDone(true)

        assertEquals("https://dl.example.com", settings.serverUrl.first())
        assertEquals("s3cret-token", settings.token.first())
        assertEquals(Engine.SERVER, settings.defaultEngine.first())
        assertEquals("de", settings.language.first())
        assertEquals(1_700_000_000_000, settings.lastYtDlpCheck.first())
        assertTrue(settings.firstRunDone.first())
        assertEquals(ServerConfig("https://dl.example.com", "s3cret-token"), settings.serverConfig())
    }

    @Test
    fun `the token is stored as ciphertext only`() = runTest {
        val file = File(tmp.root, "s.preferences_pb")
        val settings = repo(this, file = file)

        settings.setToken("s3cret-token")

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("s3cret-token"))
        assertTrue(raw.contains(FakeTokenCipher().encrypt("s3cret-token")))
    }

    @Test
    fun `an empty token clears the stored one`() = runTest {
        val settings = repo(this)
        settings.setToken("abc")

        settings.setToken("")

        assertEquals("", settings.token.first())
        assertNull(settings.serverConfig())
    }

    @Test
    fun `an undecryptable token reads as empty instead of crashing`() = runTest {
        val cipher = object : TokenCipher {
            var keyGone = false
            override fun encrypt(plain: String) = plain
            override fun decrypt(encoded: String): String = if (keyGone) error("key gone") else encoded
        }
        val settings = repo(this, cipher)
        settings.setToken("abc")
        assertEquals("abc", settings.token.first())

        cipher.keyGone = true

        assertEquals("", settings.token.first())
        assertNull(settings.serverConfig())
    }

    @Test
    fun `server config needs both url and token`() = runTest {
        val settings = repo(this)
        settings.setServerUrl("https://dl.example.com")
        assertNull(settings.serverConfig())

        settings.setToken("t")
        settings.setServerUrl("")
        assertNull(settings.serverConfig())
    }

    @Test
    fun `flows emit changes`() = runTest {
        val settings = repo(this)

        settings.defaultEngine.test {
            assertEquals(Engine.DEVICE, awaitItem())
            settings.setDefaultEngine(Engine.SERVER)
            assertEquals(Engine.SERVER, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update store adapter reads and writes the last check`() = runTest {
        val settings = repo(this)
        val store = YtDlpUpdateStoreAdapter.from(settings)

        assertNull(store.lastUpdateMillis())
        store.setLastUpdateMillis(42)

        assertEquals(42L, store.lastUpdateMillis())
        assertEquals(42L, settings.lastYtDlpCheck.first())
    }

    @Test
    fun `normalize server url`() {
        assertEquals("https://dl.example.com", normalizeServerUrl("https://dl.example.com/"))
        assertEquals("https://dl.example.com", normalizeServerUrl("  https://DL.example.com  "))
        assertEquals("https://dl.example.com:8443/app", normalizeServerUrl("https://dl.example.com:8443/app//"))
        assertEquals("", normalizeServerUrl("   "))
        assertNull(normalizeServerUrl("http://dl.example.com"))
        assertNull(normalizeServerUrl("ftp://dl.example.com"))
        assertNull(normalizeServerUrl("dl.example.com"))
        assertNull(normalizeServerUrl("https://"))
        assertNull(normalizeServerUrl("not a url"))
        assertNull(normalizeServerUrl("https://user:secret@dl.example.com"))
        assertNull(normalizeServerUrl("https://dl.example.com/?token=secret"))
        assertNull(normalizeServerUrl("https://dl.example.com/#frag"))
    }

    @Test
    fun `settings reject insecure server and unsupported language`() = runTest {
        val settings = repo(this)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { settings.setServerUrl("http://dl.example.com") }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { settings.setLanguage("fr") }
        }
        assertEquals("", settings.serverUrl.first())
        assertEquals("", settings.language.first())
    }

    @Test
    fun `fake cipher round trip and ciphertext differs`() {
        val cipher = FakeTokenCipher()
        val encrypted = cipher.encrypt("tok")

        assertNotEquals("tok", encrypted)
        assertEquals("tok", cipher.decrypt(encrypted))
    }
}
