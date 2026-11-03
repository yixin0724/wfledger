/*
 * End-to-end simulation example for WfCommons JSON.
 *
 * Runs the standard WorkflowSim flow (parse -> cluster -> plan -> schedule -> execute) on one
 * WfCommons WfFormat JSON workflow instance, showing that a JSON dataset can serve as a first-class
 * input.
 */
package org.workflowsim.examples.wfcommons;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * Runs the end-to-end standard simulation on a WfCommons JSON workflow instance.
 *
 * <p>Usage: {@code main [jsonPath]}; the default input is
 * {@code datasets/wfformat/montage/n100/montage-100-000.json}. The example uses the explicitly
 * named ready-batch Min-Min policy and enables neither clustering nor planning, so that JSON input
 * enters the same standard WorkflowSim flow as DAX.</p>
 */
public final class WfCommonsSimulationExample1 {

    private WfCommonsSimulationExample1() {}

    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 1) {
            throw new IllegalArgumentException("Usage: WfCommonsSimulationExample1 [jsonPath]");
        }
        int vmNum = 20;
        String jsonPath =
                args != null && args.length > 0 ? args[0] : "datasets/wfformat/montage/n100/montage-100-000.json";
        Path jsonFile = Paths.get(jsonPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jsonFile)) {
            throw new IllegalArgumentException("Workflow JSON does not exist or is not a regular file: " + jsonFile);
        }

        SimulationConfig config = SimulationConfig.builder(jsonFile.toString(), vmNum)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(20260901L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("wfcommons-homogeneous-local", vmNum);
        SimulationReport report = new SimulationRunner().run(config, platform);
        printReport(report);
    }

    /** Prints a compact reproducibility summary of the completed run. */
    protected static void printReport(SimulationReport report) {
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        DecimalFormat dft = new DecimalFormat("###.##");
        Log.printLine("WFJSON_SUMMARY input=" + report.getInputs().get(0).getPath()
                + " sha256=" + report.getInputs().get(0).getSha256()
                + " jobs=" + report.getTotalJobs()
                + " success=" + report.getSuccessfulJobs()
                + " failed=" + report.getFailedJobs()
                + " makespan=" + dft.format(report.getMakespan())
                + " seed=" + report.getConfig().getRandomSeed());
    }
}
