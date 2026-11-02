package com.thayford.castfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When the "Fix casting now" button should be disabled. The rule is deliberately narrow: only a run that
 * confirmed casting works, on a device that has not restarted since, may disable it. Anything else leaves the
 * owner able to run it, because the cost of a needless run is reopening the setup wizard.
 */
public class FixLogOutcomeTest {

    @Test
    public void confirmedWorkingDisablesTheButton() {
        assertTrue(FixLog.castingConfirmedWorking("HEALTHY", 5_000, 5_001));
    }

    @Test
    public void attentionLeavesTheButtonEnabled() {
        // A run that could not confirm anything must not lock the owner out of trying again.
        assertFalse(FixLog.castingConfirmedWorking("ATTENTION", 5_000, 5_001));
    }

    @Test
    public void noRecordLeavesTheButtonEnabled() {
        // Fresh install: no result has ever been written.
        assertFalse(FixLog.castingConfirmedWorking("", 0, 5_001));
    }

    @Test
    public void rebootInvalidatesAHealthyResult() {
        // Uptime restarted, so the previous "HEALTHY" describes a receiver process that no longer exists:
        // a reboot is exactly what throws the credentials away.
        assertTrue(FixLog.restartedSince(50_000, 3_000));
        assertFalse(FixLog.castingConfirmedWorking("HEALTHY", 50_000, 3_000));
    }

    @Test
    public void noRebootKeepsAHealthyResult() {
        assertFalse(FixLog.restartedSince(5_000, 50_000));
        assertTrue(FixLog.castingConfirmedWorking("HEALTHY", 5_000, 50_000));
    }

    @Test
    public void equalUptimeIsNotARestart() {
        // The same reading twice happens when the screen refreshes immediately after a run.
        assertFalse(FixLog.restartedSince(5_000, 5_000));
        assertTrue(FixLog.castingConfirmedWorking("HEALTHY", 5_000, 5_000));
    }

    @Test
    public void unknownRecordTimeIsNotEvidence() {
        assertFalse(FixLog.castingConfirmedWorking("HEALTHY", 0, 5_000));
    }

    @Test
    public void onlyHealthyIsABlockingOutcome() {
        for (FixLog.Outcome outcome : FixLog.Outcome.values()) {
            boolean expected = outcome == FixLog.Outcome.HEALTHY;
            assertTrue(outcome.name(), expected == FixLog.castingConfirmedWorking(outcome.name(), 1_000, 2_000));
        }
    }
}
