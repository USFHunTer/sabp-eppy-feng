package app.morphe.extension.sabp.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Pause at skipped passages": pause, or only offer to, for a crossed passage. */
class SkipPauseTest {
    @Test
    fun offIsAlwaysAnOffer() {
        assertFalse(SkipPause.shouldPause(SkipPause.OFF, 30))
        assertFalse(SkipPause.shouldPause(SkipPause.OFF, 5000))
    }

    @Test
    fun everyPassagePauses() {
        assertTrue(SkipPause.shouldPause(0, 30))
    }

    @Test
    fun smallerThanTheMinimumIsAnOffer() {
        assertFalse(SkipPause.shouldPause(50, 30))
        assertTrue(SkipPause.shouldPause(50, 50))
        assertTrue(SkipPause.shouldPause(50, 383))
    }
}
