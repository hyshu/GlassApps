package bio.aq.glassdisplay.streaming

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamKeyStoreTest {
    @Test
    fun parseHexKey_accepts32ByteKey() {
        val key = StreamKeyStore.parseHexKey(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        )

        assertEquals(32, key.size)
        assertEquals(0, key[0].toInt())
        assertEquals(31, key[31].toInt())
    }

    @Test
    fun parseHexKey_rejectsInvalidKey() {
        assertThrows(IOException::class.java) {
            StreamKeyStore.parseHexKey("not-a-32-byte-key")
        }
    }

    @Test
    fun parseDeviceIdentityHex_accepts8ByteIdentity() {
        val identity = StreamKeyStore.parseDeviceIdentityHex("0011223344556677")

        assertEquals(8, identity.size)
        assertEquals(0x00, identity[0].toInt())
        assertEquals(0x77, identity[7].toInt())
    }

    @Test
    fun parseDeviceIdentityHex_rejectsInvalidIdentity() {
        assertThrows(IOException::class.java) {
            StreamKeyStore.parseDeviceIdentityHex("not-an-id")
        }
    }

    @Test
    fun parseHostIdentityHex_accepts8ByteIdentity() {
        val identity = StreamKeyStore.parseHostIdentityHex("8899aabbccddeeff")

        assertEquals(8, identity.size)
        assertEquals(0x88.toByte(), identity[0])
        assertEquals(0xff.toByte(), identity[7])
    }
}
