package org.workflowsim.examples;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public static final List<String> DEFAULT_MULTIPLE_DAX_PATHS = Collections.unmodifiableList(Arrays.asList(
            "datasets/dax/epigenomics/n24/Epigenomics_24.dax",
            "datasets/dax/epigenomics/n46/Epigenomics_46.dax",
            DEFAULT_DAX_PATH));

    public static final long LEGACY_DEMO_SEED = 20260901L;

    public static final double LEGACY_DEMO_FAILURE_INTERVAL_SCALE = 7500.0;

    public static final double LEGACY_CLUSTERING_DEMO_FAILURE_INTERVAL_SCALE = 50000.0;

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
        Parameters.enableLegacySchedulingAliases();
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

    public static List<String> multipleDax(String[] args, List<String> defaults, int expectedCount) {
        if (defaults == null || defaults.size() != expectedCount || expectedCount <= 0) {
            throw new IllegalArgumentException("Invalid default multi-workflow DAX contract");
        }
        int count = args == null ? 0 : args.length;
        if (count != 0 && count != expectedCount) {
            throw new IllegalArgumentException("Expected zero or exactly " + expectedCount + " DAX input paths");
        }
        List<String> selected = count == 0 ? defaults : Arrays.asList(args);
        java.util.ArrayList<String> validated = new java.util.ArrayList<String>(selected.size());
        for (String path : selected) {
            validated.add(requireDax(path));
        }
        return Collections.unmodifiableList(validated);
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

    public static Map<String, String> parseValueOptions(String[] args, String... allowedOptions) {
        Set<String> allowed = new LinkedHashSet<String>(Arrays.asList(allowedOptions));
        Map<String, String> values = new LinkedHashMap<String, String>();
        int count = args == null ? 0 : args.length;
        for (int index = 0; index < count; index += 2) {
            String option = args[index];
            if (option == null || option.length() != 2 || option.charAt(0) != '-') {
                throw new IllegalArgumentException("Expected a single-character option at argument " + index);
            }
            if (!allowed.contains(option)) {
                throw new IllegalArgumentException("Unsupported option: " + option);
            }
            if (index + 1 >= count) {
                throw new IllegalArgumentException("Missing value for option " + option);
            }
            String value = args[index + 1];
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Option " + option + " cannot have an empty value");
            }
            if (values.put(option, value.trim()) != null) {
                throw new IllegalArgumentException("Duplicate option: " + option);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    public static String optionOrDefault(Map<String, String> options, String option, String defaultValue) {
        if (options == null) {
            throw new IllegalArgumentException("Options cannot be null");
        }
        String value = options.get(option);
        return value == null ? defaultValue : value;
    }

    public static double positiveFinite(String option, String value) {
        double result = finite(option, value);
        if (result <= 0.0) {
            throw new IllegalArgumentException(option + " must be finite and greater than zero");
        }
        return result;
    }

    public static double nonNegativeFinite(String option, String value) {
        double result = finite(option, value);
        if (result < 0.0) {
            throw new IllegalArgumentException(option + " must be finite and non-negative");
        }
        return result;
    }

    public static int nonNegativeInt(String option, String value) {
        try {
            int result = Integer.parseInt(value);
            if (result < 0) {
                throw new IllegalArgumentException(option + " must be non-negative");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " must be an integer", exception);
        }
    }

    public static long longValue(String option, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " must be a 64-bit integer", exception);
        }
    }

    public static double positiveFiniteProduct(String expression, double... factors) {
        if (factors == null || factors.length == 0) {
            throw new IllegalArgumentException("Product factors cannot be empty");
        }
        double product = 1.0;
        for (double factor : factors) {
            if (Double.isNaN(factor) || Double.isInfinite(factor) || factor <= 0.0) {
                throw new IllegalArgumentException(expression + " requires finite positive factors");
            }
            product *= factor;
            if (Double.isInfinite(product) || Double.isNaN(product)) {
                throw new IllegalArgumentException(expression + " is not finite");
            }
        }
        return product;
    }

    public static String allowedCharacters(String option, String value, String allowedCharacters) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(option + " cannot be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            if (allowedCharacters.indexOf(value.charAt(index)) < 0) {
                throw new IllegalArgumentException(option + " contains unsupported code: " + value.charAt(index));
            }
        }
        return value;
    }

    public static String allowedValue(String option, String value, String... allowedValues) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(option + " cannot be empty");
        }
        for (String allowedValue : allowedValues) {
            if (value.equals(allowedValue)) {
                return value;
            }
        }
        throw new IllegalArgumentException(option + " has unsupported value: " + value);
    }

    public static double unitInterval(String option, String value) {
        double result = nonNegativeFinite(option, value);
        if (result > 1.0) {
            throw new IllegalArgumentException(option + " must be between zero and one");
        }
        return result;
    }

    public static double openUnitInterval(String option, String value) {
        double result = unitInterval(option, value);
        if (result == 0.0 || result == 1.0) {
            throw new IllegalArgumentException(option + " must be strictly between zero and one");
        }
        return result;
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

    public static IllegalStateException failure(String exampleId, Exception cause) {
        String message = cause == null ? "unknown error" : cause.getMessage();
        Log.printLine("The simulation has been terminated due to an unexpected error: " + message);
        return new IllegalStateException("Historical example failed: " + exampleId, cause);
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

    private static double finite(String option, String value) {
        try {
            double result = Double.parseDouble(value);
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                throw new IllegalArgumentException(option + " must be finite");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " must be a number", exception);
        }
    }
}
