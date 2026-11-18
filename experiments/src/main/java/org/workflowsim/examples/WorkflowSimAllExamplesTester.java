/*
 * Copyright 2013-2014 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.workflowsim.examples;

import java.io.File;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.math3.distribution.WeibullDistribution;
import org.cloudbus.cloudsim.core.CloudSim;
import org.jdom2.Document;
import org.workflowsim.WorkflowEngine;

import com.google.gson.Gson;

public final class WorkflowSimAllExamplesTester {

    private static final long EXAMPLE_TIMEOUT_SECONDS = 90L;

    private WorkflowSimAllExamplesTester() {}

    public static void main(String[] args) throws Exception {
        if (args != null && args.length == 1 && "--list".equals(args[0])) {
            listExamples();
            return;
        }
        if (args != null && args.length != 0) {
            throw new IllegalArgumentException("WorkflowSimAllExamplesTester accepts only --list");
        }

        String classpath = runtimeClasspath();
        int completed = 0;
        for (ExampleCatalog.Descriptor descriptor : ExampleCatalog.legacyExamples()) {
            runExample(classpath, descriptor);
            completed++;
        }
        System.out.println("EXAMPLE_SUITE_COMPLETED count=" + completed);
    }

    private static void listExamples() {
        for (ExampleCatalog.Descriptor descriptor : ExampleCatalog.legacyExamples()) {
            System.out.println(descriptor.getId() + " " + descriptor.getMainClass());
        }
    }

    private static void runExample(String classpath, ExampleCatalog.Descriptor descriptor) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-cp", classpath, descriptor.getMainClass());
        builder.directory(new File(System.getProperty("user.dir")));
        builder.inheritIO();
        Process process = builder.start();
        if (!process.waitFor(EXAMPLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Historical example timed out after " + EXAMPLE_TIMEOUT_SECONDS
                    + " seconds: " + descriptor.getId());
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "Historical example failed with exit code " + process.exitValue() + ": " + descriptor.getId());
        }
    }

    static String runtimeClasspath() {
        Set<String> entries = new LinkedHashSet<String>();
        addCodeSource(entries, WorkflowSimAllExamplesTester.class);
        addCodeSource(entries, WorkflowEngine.class);
        addCodeSource(entries, CloudSim.class);
        addCodeSource(entries, Document.class);
        addCodeSource(entries, Gson.class);
        addCodeSource(entries, WeibullDistribution.class);
        if (entries.isEmpty()) {
            throw new IllegalStateException("Unable to build an isolated example runtime classpath");
        }
        StringBuilder value = new StringBuilder();
        for (String entry : entries) {
            if (value.length() > 0) {
                value.append(File.pathSeparatorChar);
            }
            value.append(entry);
        }
        return value.toString();
    }

    private static void addCodeSource(Set<String> entries, Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("No code source is available for " + type.getName());
        }
        try {
            entries.add(new File(source.getLocation().toURI()).getPath());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid code source for " + type.getName(), exception);
        }
    }

    private static String javaExecutable() {
        return new File(System.getProperty("java.home"), "bin/java").getPath();
    }
}
