package io.github.timhofmann27.opentakez.data

import java.security.KeyStore
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Needs the real AndroidKeyStore, so it runs on a device or emulator. */
class KeystoreTokenCipherTest {
    private val alias = "opentakez_test_key"
    private val cipher = KeystoreTokenCipher(alias)

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun roundTrip() {
        assertEquals("s3cret ä😀", cipher.decrypt(cipher.encrypt("s3cret ä😀")))
    }

    @Test
    fun ciphertextDiffersFromPlaintextAndIsRandomized() {
        val a = cipher.encrypt("token")
        val b = cipher.encrypt("token")

        assertNotEquals("token", a)
        assertFalse(String(Base64.getDecoder().decode(a), Charsets.ISO_8859_1).contains("token"))
        assertNotEquals(a, b) // fresh IV every time
    }

    @Test
    fun keySurvivesANewCipherInstance() {
        val encrypted = cipher.encrypt("token")

        assertEquals("token", KeystoreTokenCipher(alias).decrypt(encrypted))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val bytes = Base64.getDecoder().decode(cipher.encrypt("token"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()

        assertThrows(Exception::class.java) { cipher.decrypt(Base64.getEncoder().encodeToString(bytes)) }
    }

    @Test
    fun garbageIsRejected() {
        assertThrows(Exception::class.java) { cipher.decrypt("AAAA") }
    }

    @Test
    fun keyIsNotExportable() {
        cipher.encrypt("x")
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)

        assertEquals(null, key.encoded)
    }
}
