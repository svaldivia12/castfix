package com.thayford.castfix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Epoch-timestamp parsing, used only to describe the log in the run log. None of it decides an outcome: the
 * handshake does that, because these timestamps were the source of three wrong verdicts on hardware.
 */
public class EpochParsingTest {

    @Test
    public void parsesPaddedEpochAndTruncatesFraction() {
        assertEquals(1793576660L, ReceiverState.epochOf(
                "         1793576660.018  2080  2779 I cast_ClientAuthCredsProvisioning: Loading"));
    }

    @Test
    public void rejectsTheDefaultDateFormat() {
        // "10-31 05:49:34.000" starts with digits too; treating it as an epoch was a real bug.
        assertEquals(0, ReceiverState.epochOf("10-31 05:49:34.000  2165  2660 E tag: text"));
        assertEquals(0, ReceiverState.epochOf("11-01 20:42:48.015  2080  2080 I cast_shell: x"));
    }

    @Test
    public void rejectsLinesWithoutAFraction() {
        // logcat -v epoch always prints a fraction; a bare number is not a timestamp.
        assertEquals(0, ReceiverState.epochOf("1793576660  2080 I tag"));
    }

    @Test
    public void rejectsShortNumbers() {
        assertEquals(0, ReceiverState.epochOf("10.5  2080 I tag"));
        assertEquals(0, ReceiverState.epochOf("12345678.9  2080 I tag"));
    }

    @Test
    public void rejectsNonLogLines() {
        assertEquals(0, ReceiverState.epochOf("--------- beginning of main"));
        assertEquals(0, ReceiverState.epochOf(""));
    }
}
