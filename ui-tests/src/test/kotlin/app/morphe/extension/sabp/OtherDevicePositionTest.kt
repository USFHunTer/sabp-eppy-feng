package app.morphe.extension.sabp

import ak.alizandro.smartaudiobookplayer.BookDataBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InvalidClassException
import java.io.ObjectOutputStream

/** The other device's position: reading SABP's position.sabp.dat and deciding whether to offer it. */
class OtherDevicePositionTest {
    private fun bytes(o: Any) = ByteArrayOutputStream().also { ObjectOutputStream(it).use { s -> s.writeObject(o) } }.toByteArray()

    private fun there(file: String, sec: Int, at: Long) = OtherDevicePosition(file, sec, at)

    @Test
    fun readsSabpsBackup() {
        val p = OtherDevicePosition.parse(ByteArrayInputStream(bytes(BookDataBackup("Part 2.m4b", 3725, 1_700_000_000_000L))))!!
        assertEquals("Part 2.m4b", p.fileName)
        assertEquals(3725, p.seconds)
        assertEquals(1_700_000_000_000L, p.lastPlayed)
    }

    @Test
    fun noFileNameMeansNothingToOffer() {
        assertNull(OtherDevicePosition.parse(ByteArrayInputStream(bytes(BookDataBackup(null, 10, 5L)))))
    }

    @Test
    fun refusesAnythingButSabpsClassesAndPlainValues() {
        try {
            OtherDevicePosition.parse(ByteArrayInputStream(bytes(java.util.HashMap<String, String>())))
            throw AssertionError("a HashMap was deserialised")
        } catch (e: InvalidClassException) {
            // expected
        }
        assertTrue(OtherDevicePosition.allowed("ak.alizandro.smartaudiobookplayer.EqualizerLevels"))
        assertTrue(OtherDevicePosition.allowed("[S"))
        assertFalse(OtherDevicePosition.allowed("java.util.HashMap"))
        assertFalse(OtherDevicePosition.allowed("[Ljava.lang.Object;"))
    }

    @Test
    fun offersOnlyTheOtherDevicesPositionElsewhere() {
        val saved = 1_000L // this device's last save of the file
        fun cmp(t: OtherDevicePosition?, savedHere: Long? = saved) = OtherDevicePosition.compare("a.mp3", 100, savedHere, t)
        assertEquals("written here", OtherDevicePosition.MINE, cmp(there("a.mp3", 900, saved)))
        assertEquals("within 30 s", OtherDevicePosition.SAME_PLACE, cmp(there("a.mp3", 130, 2_000)))
        assertEquals("31 s on", OtherDevicePosition.OFFER, cmp(there("a.mp3", 131, 2_000)))
        assertEquals("31 s back", OtherDevicePosition.OFFER, cmp(there("a.mp3", 69, 2_000)))
        assertEquals("another file", OtherDevicePosition.OFFER, cmp(there("b.mp3", 100, 2_000)))
        // The other device's clock may be behind: who wrote it decides, not which stamp is bigger.
        assertEquals("other device, earlier clock", OtherDevicePosition.OFFER, cmp(there("a.mp3", 900, 500)))
        assertEquals("no save recorded yet: the place decides", OtherDevicePosition.OFFER, cmp(there("a.mp3", 900, 500), null))
        assertEquals("no file", OtherDevicePosition.NONE, cmp(null))
    }
}
