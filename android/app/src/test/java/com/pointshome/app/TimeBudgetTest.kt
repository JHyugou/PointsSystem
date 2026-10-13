package com.pointshome.app

import org.junit.Assert.*
import org.junit.Test

class TimeBudgetTest {
    private class Wallet(var balance: Long, var now: Long = 0) {
        fun adjust(delta: Long): Long { val old = balance; balance = (old + delta).coerceAtLeast(0); return balance - old }
        fun budget() = TimeBudget({ balance }, ::adjust, { now })
    }
    @Test fun countsForegroundOnlyAndRefundsUnusedReservationOnPause() {
        val wallet = Wallet(60_000); val budget = wallet.budget()
        assertTrue(budget.resume()); assertEquals(55_000, wallet.balance)
        wallet.now = 2_000; assertEquals(58_000, budget.availableMillis())
        budget.pause(); assertEquals(58_000, wallet.balance)
        wallet.now = 300_000; assertEquals(58_000, budget.availableMillis())
        assertTrue(budget.resume()); wallet.now += 1_000; budget.pause()
        assertEquals(57_000, wallet.balance)
    }
    @Test fun crashesNeverResurrectReservedTimeAndZeroIsBlocked() {
        val wallet = Wallet(1_000); val beforeCrash = wallet.budget()
        assertTrue(beforeCrash.resume()); assertEquals(0, wallet.balance)
        assertFalse(wallet.budget().resume())
        wallet.now = 1_000; assertFalse(beforeCrash.resume()); assertEquals(0, beforeCrash.availableMillis())
    }
    @Test fun grantDuringUseIsPreservedAndLateTickDoesNotGiveFreeTime() {
        val wallet = Wallet(10_000); val budget = wallet.budget()
        assertTrue(budget.resume()); wallet.adjust(60_000)
        wallet.now = 5_250; assertTrue(budget.resume())
        assertEquals(64_750, budget.availableMillis())
        wallet.now += 2_000; budget.pause(); assertEquals(62_750, wallet.balance)
    }
    @Test fun repeatedPauseDoesNotRefundTwiceAndRepeatedResumeDoesNotDoubleCharge() {
        val wallet = Wallet(20_000); val budget = wallet.budget()
        assertTrue(budget.resume()); assertTrue(budget.resume()); assertEquals(15_000, wallet.balance)
        wallet.now = 1_234; budget.pause(); budget.pause(); assertEquals(18_766, wallet.balance)
    }
    @Test fun delayedScreenOffWorkPausesAtTheScreenOffInstant() {
        val wallet = Wallet(60_000); val budget = wallet.budget()
        assertTrue(budget.resume()); wallet.now = 4_000
        budget.pause(1_000)
        assertEquals(59_000, wallet.balance)
        budget.pause(1_000); assertEquals(59_000, wallet.balance)
    }
    @Test fun oldPauseSignalCannotRefundMoreThanTheCurrentReservation() {
        val wallet = Wallet(10_000); val budget = wallet.budget()
        assertTrue(budget.resume()); wallet.now = 6_000; assertTrue(budget.resume())
        budget.pause(1_000); assertEquals(4_000, wallet.balance)
    }
}
