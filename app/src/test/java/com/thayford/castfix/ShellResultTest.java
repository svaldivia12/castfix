package com.thayford.castfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * How a shell result is judged. adbd's legacy shell stream reports its exit status separately from the
 * output, and it can arrive after the stream has already ended; treating that "unknown" as success is what
 * once let an interrupted `pm enable` look like it had worked.
 */
public class ShellResultTest {

    @Test
    public void zeroIsSuccess() {
        assertTrue(new Shell.Result("", 0).ok());
        assertEquals("", new Shell.Result("", 0).problem());
    }

    @Test
    public void nonZeroIsFailureAndKeepsTheFirstLine() {
        Shell.Result result = new Shell.Result(
                "Exception occurred while executing 'enable':\n  at android.server", 255);
        assertFalse(result.ok());
        assertTrue(result.problem(), result.problem().startsWith("Exception occurred"));
    }

    @Test
    public void unknownStatusIsNotSuccess() {
        // -1 means the marker never came back. The caller verifies the device state anyway, but this must not
        // be reported as success on its own.
        Shell.Result result = new Shell.Result("", -1);
        assertFalse(result.ok());
        assertEquals("no exit status came back", result.problem());
    }

    @Test
    public void emptyFailureStillExplainsItself() {
        assertEquals("exit 1", new Shell.Result("", 1).problem());
    }

    @Test
    public void outputIsTrimmedToOneLine() {
        Shell.Result result = new Shell.Result("  first line  \nsecond\nthird", 2);
        assertEquals("first line", result.problem().trim());
    }
}
