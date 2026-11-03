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

/**
 * Shared input, randomness, and result-boundary contract for the historical teaching command-line
 * examples.
 *
 * <p>This class serves only the legacy API examples retained in {@code experiments}. It neither
 * changes the algorithm contract of {@code SimulationRunner} nor promotes these examples to
 * reproducible experiment campaigns.</p>
 */
public final class ExampleCliSupport {

    /** Default input that passed the strict DAX input contract and suits the historical
     * single-workflow teaching example. */
    public static final String DEFAULT_DAX_PATH = "datasets/dax/epigenomics/n100/Epigenomics_100.dax";

    /** Three strict input candidates used by the multi-workflow historical example. */
    public static final List<String> DEFAULT_MULTIPLE_DAX_PATHS = Collections.unmodifiableList(Arrays.asList(
            "datasets/dax/epigenomics/n24/Epigenomics_24.dax",
            "datasets/dax/epigenomics/n46/Epigenomics_46.dax",
            DEFAULT_DAX_PATH));

    /** Fixed root seed for the historical teaching scenario; formal research experiments must
     * still record their own seed plan explicitly. */
    public static final long LEGACY_DEMO_SEED = 20260901L;

    /**
     * Weibull inter-arrival scale used by the Epigenomics n100 historical fault-tolerance teaching
     * scenario.
     *
     * <p>The value exists only so that the legacy example exhibits low-frequency failures and
     * retries within a bounded time; it is not a parameter calibrated from real platform failure
     * records.</p>
     */
    public static final double LEGACY_DEMO_FAILURE_INTERVAL_SCALE = 7500.0;

    /**
     * Sparser teaching scale used by the legacy reclustering example that generates failure streams
     * per DAG level.
     *
     * <p>Each level holds an independent random stream, so the scale of the single-stream example
     * cannot be reused directly; the value only bounds the default teaching scenario and does not
     * represent a real failure process.</p>
     */
    public static final double LEGACY_CLUSTERING_DEMO_FAILURE_INTERVAL_SCALE = 50000.0;

    /** Unified machine-readable success-boundary prefix. */
    public static final String COMPLETION_PREFIX = "EXAMPLE_COMPLETED";

    private static final ThreadLocal<String> ACTIVE_EXAMPLE_ID = new ThreadLocal<String>();

    private ExampleCliSupport() {}

    /**
     * Begin a historical teaching scenario: clear WorkflowSim static state possibly left over from
     * the previous scenario and install the fixed seed.
     *
     * <p>The command-line smoke run still executes each example in a separate JVM; the cleanup here
     * only gives manual same-JVM invocations an explicit boundary and cannot replace process
     * isolation.</p>
     *
     * @param exampleId stable and non-empty example identifier from the catalog
     */
    public static void begin(String exampleId) {
        begin(exampleId, LEGACY_DEMO_SEED);
    }

    /**
     * Begin a historical teaching scenario and install the root seed explicitly supplied by the
     * caller.
     *
     * <p>The historical examples name the deprecated scheduling labels, so this entry point
     * also declares the legacy alias set of {@code Parameters} (AUD-F10); the declaration is
     * what lets the scheduler bind those labels to their historical policies again, and it is
     * cleared by the same legacy state reset that ends the scenario.</p>
     *
     * @param exampleId stable and non-empty example identifier from the catalog
     * @param rootSeed root seed to record and replay for this scenario
     */
    public static void begin(String exampleId, long rootSeed) {
        if (exampleId == null || exampleId.trim().isEmpty()) {
            throw new IllegalArgumentException("Example id cannot be empty");
        }
        resetLegacyState();
        Parameters.setRandomSeed(rootSeed);
        Parameters.enableLegacySchedulingAliases();
        ACTIVE_EXAMPLE_ID.set(exampleId);
    }

    /**
     * Parse the zero-or-one DAX argument of a single-workflow example, and verify that the input
     * exists before any CloudSim entity is created.
     *
     * @param args command-line arguments
     * @param defaultPath controlled default input used when no argument is supplied
     * @return readable DAX file path, preserving the relative-path semantics supplied by the caller
     */
    public static String singleDax(String[] args, String defaultPath) {
        int count = args == null ? 0 : args.length;
        if (count > 1) {
            throw new IllegalArgumentException("Expected zero or one DAX input path");
        }
        String path = count == 0 ? defaultPath : args[0];
        return requireDax(path);
    }

    /**
     * Parse the inputs of a multi-workflow example: use the controlled default set when no argument
     * is supplied; when supplied explicitly, exactly {@code expectedCount} DAX paths are required.
     *
     * @param args command-line arguments
     * @param defaults input set used when no argument is supplied
     * @param expectedCount required number of paths when they are supplied explicitly
     * @return unmodifiable list of paths, each verified in turn
     */
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

    /** Verify that a DAX input file exists and is a regular file. */
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

    /**
     * Strictly parse a legacy command line composed only of "single-character flag plus one value"
     * pairs.
     *
     * <p>Duplicate flags, unknown flags, empty flags, missing values, and empty values all fail
     * immediately. Numeric and domain constraints are checked further by the caller through the
     * parsing helpers of this class.</p>
     *
     * @param args command-line arguments
     * @param allowedOptions supported flags, for example {@code -d}
     * @return unmodifiable option map stored in order of appearance
     */
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

    /** Return an optional argument value; return the caller-declared default when it is absent. */
    public static String optionOrDefault(Map<String, String> options, String option, String defaultValue) {
        if (options == null) {
            throw new IllegalArgumentException("Options cannot be null");
        }
        String value = options.get(option);
        return value == null ? defaultValue : value;
    }

    /** Parse a finite floating-point argument strictly greater than zero. */
    public static double positiveFinite(String option, String value) {
        double result = finite(option, value);
        if (result <= 0.0) {
            throw new IllegalArgumentException(option + " must be finite and greater than zero");
        }
        return result;
    }

    /** Parse a finite floating-point argument greater than or equal to zero. */
    public static double nonNegativeFinite(String option, String value) {
        double result = finite(option, value);
        if (result < 0.0) {
            throw new IllegalArgumentException(option + " must be finite and non-negative");
        }
        return result;
    }

    /** Parse an integer argument greater than or equal to zero. */
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

    /** Parse a signed 64-bit integer, for example an explicit experiment root seed. */
    public static long longValue(String option, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " must be a 64-bit integer", exception);
        }
    }

    /**
     * Compute the product of a set of strictly positive finite factors, and fail on overflow.
     *
     * @param expression human-readable argument expression
     * @param factors multipliers that have each already passed domain validation
     * @return finite positive product
     */
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

    /**
     * Verify that the option value consists only of allowed single-character codes and contains at
     * least one character.
     */
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

    /**
     * Verify that the option value belongs exactly to the given finite set.
     *
     * @param option command-line option name
     * @param value value to verify
     * @param allowedValues accepted exact values
     * @return the original value, so that it can be assigned directly to a caller variable
     */
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

    /** Parse a finite floating-point argument in the closed interval {@code [0, 1]}. */
    public static double unitInterval(String option, String value) {
        double result = nonNegativeFinite(option, value);
        if (result > 1.0) {
            throw new IllegalArgumentException(option + " must be between zero and one");
        }
        return result;
    }

    /** Parse a finite floating-point argument in the open interval {@code (0, 1)}. */
    public static double openUnitInterval(String option, String value) {
        double result = unitInterval(option, value);
        if (result == 0.0 || result == 1.0) {
            throw new IllegalArgumentException(option + " must be strictly between zero and one");
        }
        return result;
    }

    /**
     * Emit the stable completion boundary. In fault-tolerance teaching scenarios a failed Job may
     * represent a recoverable intermediate attempt, so such failures are counted as they are.
     *
     * @param jobs Job list returned by this CloudSim event loop
     */
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

    /**
     * Convert a historical example error into an unchecked exception that makes the CLI and Maven
     * fail correctly.
     */
    public static IllegalStateException failure(String exampleId, Exception cause) {
        String message = cause == null ? "unknown error" : cause.getMessage();
        Log.printLine("The simulation has been terminated due to an unexpected error: " + message);
        return new IllegalStateException("Historical example failed: " + exampleId, cause);
    }

    /**
     * Clear the example identifier registered on this thread and the historical WorkflowSim static
     * state.
     */
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
