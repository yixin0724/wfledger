package org.cloudbus.cloudsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the controlled error channel added to the vendored CloudSim {@link Log} for the
 * legacy clustering code (AUD-F19): the message line plus the throwable stack trace go
 * through the configured output and respect the global disable switch, so swallowing
 * exceptions into raw {@code printStackTrace} calls can be replaced without changing
 * any control flow.
 */
public class LogPrintErrorTest {

    private OutputStream originalOutput;
    private boolean originalDisabled;
    private ByteArrayOutputStream sink;

    @BeforeEach
    public void captureLogState() {
        originalOutput = Log.getOutput();
        originalDisabled = Log.isDisabled();
        Log.setDisabled(false);
        sink = new ByteArrayOutputStream();
        Log.setOutput(sink);
    }

    @AfterEach
    public void restoreLogState() {
        Log.setOutput(originalOutput);
        Log.setDisabled(originalDisabled);
    }

    @Test
    public void printErrorWritesMessageAndStackTraceToTheConfiguredOutput() {
        Log.printError("candidate selection failed", new IllegalStateException("boom"));
        String written = new String(sink.toByteArray());
        assertTrue(written.contains("candidate selection failed"));
        assertTrue(written.contains("java.lang.IllegalStateException: boom"));
        assertTrue(written.contains("\tat "));
    }

    @Test
    public void printErrorIsSilentWhileLoggingIsDisabled() {
        Log.disable();
        try {
            Log.printError("candidate selection failed", new IllegalStateException("boom"));
        } finally {
            Log.enable();
        }
        assertEquals(0, sink.toByteArray().length);
    }

    @Test
    public void printErrorToleratesAMissingMessageOrThrowable() {
        Log.printError("only message", null);
        assertTrue(new String(sink.toByteArray()).contains("only message"));
        Log.printError(null, new IllegalArgumentException("no message"));
        assertTrue(new String(sink.toByteArray()).contains("java.lang.IllegalArgumentException: no message"));
    }
}
