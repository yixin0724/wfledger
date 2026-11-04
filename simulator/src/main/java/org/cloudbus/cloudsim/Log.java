/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

/*
 * Local modifications to the vendored CloudSim source -- this repository maintains its copy:
 *
 * - Semantic addition (AUD-F19, commit 1f316c9, 2026-11-03): printError(String, Throwable),
 *   a controlled error channel that respects the disabled flag, prints an optional message
 *   line, and prints an optional stack trace through the configured output stream.
 *
 * - Repository-wide maintenance also diverges this tree from upstream CloudSim: format
 *   normalization (commits cc08dd1 and 7ad2a69), javadoc translation to English (3950109),
 *   and the removal of 68 vendored dead files (f27ba8a). A byte-for-byte refresh from
 *   upstream would silently drop printError and the normalization; re-apply the semantic
 *   addition listed above when refreshing.
 */

package org.cloudbus.cloudsim;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * The Log class used for performing loggin of the simulation process. It provides the ability to
 * substitute the output stream by any OutputStream subclass.
 *
 * @author Anton Beloglazov
 * @since CloudSim Toolkit 2.0
 */
public class Log {

    /** The Constant LINE_SEPARATOR. */
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    /** The output. */
    private static OutputStream output;

    /** The disable output flag. */
    private static boolean disabled;

    /**
     * Prints the message.
     *
     * @param message the message
     */
    public static void print(String message) {
        if (!isDisabled()) {
            try {
                getOutput().write(message.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Prints the message passed as a non-String object.
     *
     * @param message the message
     */
    public static void print(Object message) {
        if (!isDisabled()) {
            print(String.valueOf(message));
        }
    }

    /**
     * Prints the line.
     *
     * @param message the message
     */
    public static void printLine(String message) {
        if (!isDisabled()) {
            print(message + LINE_SEPARATOR);
        }
    }

    /**
     * Prints the empty line.
     */
    public static void printLine() {
        if (!isDisabled()) {
            print(LINE_SEPARATOR);
        }
    }

    /**
     * Prints the line passed as a non-String object.
     *
     * @param message the message
     */
    public static void printLine(Object message) {
        if (!isDisabled()) {
            printLine(String.valueOf(message));
        }
    }

    /**
     * Prints a string formated as in String.format().
     *
     * @param format the format
     * @param args the args
     */
    public static void format(String format, Object... args) {
        if (!isDisabled()) {
            print(String.format(format, args));
        }
    }

    /**
     * Prints a line formated as in String.format().
     *
     * @param format the format
     * @param args the args
     */
    public static void formatLine(String format, Object... args) {
        if (!isDisabled()) {
            printLine(String.format(format, args));
        }
    }

    /**
     * Prints an error message followed by the throwable's stack trace to the configured
     * output. Both arguments are optional, and the call is silent while logging is
     * disabled (AUD-F19).
     *
     * @param message the error message line, or null to print the stack trace only
     * @param t the throwable whose stack trace is printed, or null to print the message only
     */
    public static void printError(String message, Throwable t) {
        if (isDisabled()) {
            return;
        }
        if (message != null) {
            printLine(message);
        }
        if (t != null) {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(getOutput()));
            t.printStackTrace(writer);
            writer.flush();
        }
    }

    /**
     * Sets the output.
     *
     * @param _output the new output
     */
    public static void setOutput(OutputStream _output) {
        output = _output;
    }

    /**
     * Gets the output.
     *
     * @return the output
     */
    public static OutputStream getOutput() {
        if (output == null) {
            setOutput(System.out);
        }
        return output;
    }

    /**
     * Sets the disable output flag.
     *
     * @param _disabled the new disabled
     */
    public static void setDisabled(boolean _disabled) {
        disabled = _disabled;
    }

    /**
     * Checks if the output is disabled.
     *
     * @return true, if is disable
     */
    public static boolean isDisabled() {
        return disabled;
    }

    /**
     * Disables the output.
     */
    public static void disable() {
        setDisabled(true);
    }

    /**
     * Enables the output.
     */
    public static void enable() {
        setDisabled(false);
    }
}
