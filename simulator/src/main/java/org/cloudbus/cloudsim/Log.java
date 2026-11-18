/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

public class Log {

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    private static OutputStream output;

    private static boolean disabled;

    public static void print(String message) {
        if (!isDisabled()) {
            try {
                getOutput().write(message.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void print(Object message) {
        if (!isDisabled()) {
            print(String.valueOf(message));
        }
    }

    public static void printLine(String message) {
        if (!isDisabled()) {
            print(message + LINE_SEPARATOR);
        }
    }

    public static void printLine() {
        if (!isDisabled()) {
            print(LINE_SEPARATOR);
        }
    }

    public static void printLine(Object message) {
        if (!isDisabled()) {
            printLine(String.valueOf(message));
        }
    }

    public static void format(String format, Object... args) {
        if (!isDisabled()) {
            print(String.format(format, args));
        }
    }

    public static void formatLine(String format, Object... args) {
        if (!isDisabled()) {
            printLine(String.format(format, args));
        }
    }

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

    public static void setOutput(OutputStream _output) {
        output = _output;
    }

    public static OutputStream getOutput() {
        if (output == null) {
            setOutput(System.out);
        }
        return output;
    }

    public static void setDisabled(boolean _disabled) {
        disabled = _disabled;
    }

    public static boolean isDisabled() {
        return disabled;
    }

    public static void disable() {
        setDisabled(true);
    }

    public static void enable() {
        setDisabled(false);
    }
}
