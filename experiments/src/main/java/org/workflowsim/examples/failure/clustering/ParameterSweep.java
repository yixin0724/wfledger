/*
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.workflowsim.examples.failure.clustering;

import org.workflowsim.examples.ExampleCliSupport;

public final class ParameterSweep {

    private static final String DEFAULT_DAX = ExampleCliSupport.DEFAULT_DAX_PATH;
    private static final String DEFAULT_CLUSTERING = "DR";
    private static final String SMOKE_COMPLETION_PREFIX = "PARAMETER_SWEEP_SMOKE_COMPLETED";

    private ParameterSweep() {}

    public static void main(String[] args) {
        if (args == null || args.length != 1 || !"--smoke".equals(args[0])) {
            throw new IllegalArgumentException("ParameterSweep is not a default experiment runner. "
                    + "Use --smoke for one bounded compatibility run; implement research scans "
                    + "as a protocol-driven study campaign.");
        }
        double makespan =
                execute("150000", 50.0, 10.0, 2.0, 30.0, DEFAULT_CLUSTERING, ExampleCliSupport.LEGACY_DEMO_SEED);
        if (!Double.isFinite(makespan) || makespan <= 0.0) {
            throw new IllegalStateException("Parameter sweep smoke produced an invalid makespan");
        }
        System.out.println(SMOKE_COMPLETION_PREFIX + " replications=1 makespan=" + makespan);
    }

    public static double execute(
            String failureScale,
            double queueScale,
            double queuePriorWeight,
            double queueShape,
            double failurePriorWeight,
            String clustering) {
        return execute(
                failureScale,
                queueScale,
                queuePriorWeight,
                queueShape,
                failurePriorWeight,
                clustering,
                ExampleCliSupport.LEGACY_DEMO_SEED);
    }

    public static double execute(
            String failureScale,
            double queueScale,
            double queuePriorWeight,
            double queueShape,
            double failurePriorWeight,
            String clustering,
            long rootSeed) {
        String[] args = {
            "-d",
            DEFAULT_DAX,
            "-q",
            Double.toString(queueScale),
            "-w",
            Double.toString(queuePriorWeight),
            "-s",
            Double.toString(queueShape),
            "-p",
            failureScale,
            "-t",
            Double.toString(failurePriorWeight),
            "-c",
            clustering,
            "-r",
            Long.toString(rootSeed)
        };
        return FaultTolerantClusteringExample5.main2(args);
    }

    public static double execute100(
            String failureScale,
            double queueScale,
            double queuePriorWeight,
            double queueShape,
            double failurePriorWeight,
            String clustering) {
        return executeReplications(
                failureScale,
                queueScale,
                queuePriorWeight,
                queueShape,
                failurePriorWeight,
                clustering,
                100,
                ExampleCliSupport.LEGACY_DEMO_SEED);
    }

    public static double executeReplications(
            String failureScale,
            double queueScale,
            double queuePriorWeight,
            double queueShape,
            double failurePriorWeight,
            String clustering,
            int replications,
            long rootSeed) {
        if (replications <= 0) {
            throw new IllegalArgumentException("replications must be greater than zero");
        }
        double sum = 0.0;
        for (int replication = 0; replication < replications; replication++) {
            double makespan = execute(
                    failureScale,
                    queueScale,
                    queuePriorWeight,
                    queueShape,
                    failurePriorWeight,
                    clustering,
                    rootSeed + replication);
            if (!Double.isFinite(makespan) || makespan <= 0.0) {
                throw new IllegalStateException("Replication " + replication + " produced an invalid makespan");
            }
            sum += makespan;
            if (!Double.isFinite(sum)) {
                throw new IllegalStateException("Mean makespan accumulation overflowed");
            }
        }
        return sum / replications;
    }
}
