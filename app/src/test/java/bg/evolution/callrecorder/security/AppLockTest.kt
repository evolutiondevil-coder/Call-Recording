package bg.evolution.callrecorder.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000L
    private var wall = 1_700_000_000_000L

    private fun newLock() = AppLock(context, clock = { now }, wallClock = { wall })

    @Before
    fun reset() = newLock().disable()

    @Test
    fun startsUnlockedWithoutPin() {
        val lock = newLock()
        assertFalse(lock.config.value.enabled)
        assertFalse(lock.locked.value)
    }

    @Test
    fun pinIsStoredOnlyAsHashAndLocksANewProcess() {
        newLock().setPin("4821")
        val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any { it.toString().contains("4821") })

        val restarted = newLock()
        assertTrue(restarted.config.value.enabled)
        assertEquals(4, restarted.config.value.pinLength)
        assertTrue(restarted.locked.value)
        assertEquals(AppLock.Result.Ok, restarted.verify("4821"))
        assertEquals(AppLock.Result.Wrong(4), restarted.verify("4822"))
    }

    @Test
    fun invalidPinsAreRejected() {
        assertFalse(AppLock.isValidPin("123"))
        assertFalse(AppLock.isValidPin("123456789"))
        assertFalse(AppLock.isValidPin("12a4"))
        assertTrue(AppLock.isValidPin("0000"))
        assertTrue(AppLock.isValidPin("12345678"))
    }

    @Test
    fun wrongPinsLeadToGrowingWaits() {
        val lock = newLock().apply { setPin("2580") }
        repeat(4) { i -> assertEquals(AppLock.Result.Wrong(4 - i), lock.verify("0000")) }
        assertEquals(AppLock.Result.LockedOut(30_000), lock.verify("0000"))
        // Even the right PIN has to wait.
        assertTrue(lock.verify("2580") is AppLock.Result.LockedOut)
        wall += 30_000
        assertEquals(0, lock.lockoutRemainingMs())
        repeat(4) { lock.verify("1111") }
        assertEquals(AppLock.Result.LockedOut(60_000), lock.verify("1111"))
        wall += 60_000
        assertEquals(AppLock.Result.Ok, lock.verify("2580"))
        // A correct PIN starts the count again.
        assertEquals(AppLock.Result.Wrong(4), lock.verify("1111"))
    }

    @Test
    fun turningTheClockBackNeverMeansAnEndlessWait() {
        val lock = newLock().apply { setPin("2580") }
        repeat(5) { lock.verify("0000") }
        wall -= 365L * 24 * 3600 * 1000
        assertTrue(lock.lockoutRemainingMs() <= 15 * 60_000L)
        assertEquals(15 * 60_000L, AppLock.lockoutDuration(10))
    }

    @Test
    fun locksAgainAfterTheChosenTime() {
        val lock = newLock().apply { setPin("2580"); setTimeout(60_000) }
        assertFalse(lock.locked.value)
        lock.onHidden(); now += 30_000; lock.onShown()
        assertFalse(lock.locked.value)
        lock.onHidden(); now += 61_000; lock.onShown()
        assertTrue(lock.locked.value)
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    @Test
    fun immediatelyLocksOnEveryReturnButNotForItsOwnScreens() {
        val lock = newLock().apply { setPin("2580"); setTimeout(0) }
        // The app opens the contact picker: coming back from it doesn't ask for the PIN.
        lock.onOwnScreenLaunch(); now += 200; lock.onHidden(); now += 20_000; lock.onShown()
        assertFalse(lock.locked.value)
        // ...unless the user stays away for long.
        lock.onOwnScreenLaunch(); now += 200; lock.onHidden(); now += 6 * 60_000; lock.onShown()
        assertTrue(lock.locked.value)
        lock.unlock()
        // An old "own screen" mark doesn't count for leaving with the Home button later.
        lock.onOwnScreenLaunch(); now += 10_000; lock.onHidden(); now += 1_000; lock.onShown()
        assertTrue(lock.locked.value)
        lock.unlock()
        // Leaving with the Home button.
        lock.onHidden(); now += 1; lock.onShown()
        assertTrue(lock.locked.value)
    }

    @Test
    fun fingerprintPromptDoesNotRelock() {
        val lock = newLock().apply { setPin("2580"); setTimeout(0) }
        lock.onPromptShowing(true)
        lock.onHidden(); now += 5_000; lock.onShown()
        lock.onPromptShowing(false)
        assertFalse(lock.locked.value)
    }

    @Test
    fun disableForgetsEverything() {
        val lock = newLock().apply { setPin("2580"); setBiometric(true); setTimeout(300_000) }
        lock.disable()
        val c = newLock().config.value
        assertEquals(AppLock.Config(), c)
        lock.onHidden(); now += 10 * 60_000; lock.onShown()
        assertFalse(lock.locked.value)
    }

    @Test
    fun changingThePinUsesANewSalt() {
        val lock = newLock()
        val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
        lock.setPin("2580")
        val first = prefs.getString("pin_hash", null)
        lock.setPin("2580")
        assertNotEquals(first, prefs.getString("pin_hash", null))
        assertEquals(AppLock.Result.Ok, lock.verify("2580"))
    }
}
