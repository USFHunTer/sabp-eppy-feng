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
    fun offersOnlyNewerAndElsewhere() {
        val here = 1_000L
        assertFalse("older", OtherDevicePosition.worthOffering("a.mp3", 100, here, there("a.mp3", 900, here - 1)))
        assertFalse("same time: this device wrote it", OtherDevicePosition.worthOffering("a.mp3", 100, here, there("a.mp3", 900, here)))
        assertFalse("within 30 s", OtherDevicePosition.worthOffering("a.mp3", 100, here, there("a.mp3", 130, here + 1)))
        assertTrue("31 s on", OtherDevicePosition.worthOffering("a.mp3", 100, here, there("a.mp3", 131, here + 1)))
        assertTrue("31 s back", OtherDevicePosition.worthOffering("a.mp3", 100, here, there("a.mp3", 69, here + 1)))
        assertTrue("another file", OtherDevicePosition.worthOffering("a.mp3", 100, here, there("b.mp3", 100, here + 1)))
        assertFalse("no file", OtherDevicePosition.worthOffering("a.mp3", 100, here, null))
    }
}
