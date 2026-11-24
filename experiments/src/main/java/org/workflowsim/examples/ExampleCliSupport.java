package org.workflowsim.examples;

import java.io.File;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.Job;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

public final class ExampleCliSupport {

    public static final String DEFAULT_DAX_PATH = "datasets/dax/epigenomics/n100/Epigenomics_100.dax";

    public static final long LEGACY_DEMO_SEED = 20260901L;

    public static final String COMPLETION_PREFIX = "EXAMPLE_COMPLETED";

    private static final ThreadLocal<String> ACTIVE_EXAMPLE_ID = new ThreadLocal<String>();

    private ExampleCliSupport() {}

    public static void begin(String exampleId) {
        begin(exampleId, LEGACY_DEMO_SEED);
    }

    public static void begin(String exampleId, long rootSeed) {
        if (exampleId == null || exampleId.trim().isEmpty()) {
            throw new IllegalArgumentException("Example id cannot be empty");
        }
        resetLegacyState();
        Parameters.setRandomSeed(rootSeed);
        ACTIVE_EXAMPLE_ID.set(exampleId);
    }

    public static String singleDax(String[] args, String defaultPath) {
        int count = args == null ? 0 : args.length;
        if (count > 1) {
            throw new IllegalArgumentException("Expected zero or one DAX input path");
        }
        String path = count == 0 ? defaultPath : args[0];
        return requireDax(path);
    }

    public static String requireDax(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("DAX input path cannot be empty");
        }
        File dax = new File(path.trim());
        if (!dax.isFile()) {
            throw new IllegalArgumentException("DAX input does not exist: " + dax.getAbsolutePath());
        }
        return path.trim();
    }

    public static void reportCompletion(List<Job> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            throw new IllegalStateException("Example produced no jobs");
        }
        int successful = 0;
        int failed = 0;
        for (Job job : jobs) {
            if (job.getCloudletStatus() == Cloudlet.SUCCESS) {
                successful++;
            } else if (job.getCloudletStatus() == Cloudlet.FAILED) {
                failed++;
            }
        }
        String id = ACTIVE_EXAMPLE_ID.get();
        if (id == null) {
            id = "unregistered";
        }
        Log.printLine(COMPLETION_PREFIX + " id=" + id
                + " totalJobs=" + jobs.size()
                + " successfulJobs=" + successful
                + " failedJobs=" + failed);
    }

    public static void end() {
        resetLegacyState();
        ACTIVE_EXAMPLE_ID.remove();
    }

    private static void resetLegacyState() {
        if (CloudSim.running()) {
            CloudSim.stopSimulation();
        }
        FailureGenerator.reset();
        FailureMonitor.reset();
        FailureParameters.reset();
        ReplicaCatalog.reset();
        Parameters.reset();
    }
}
