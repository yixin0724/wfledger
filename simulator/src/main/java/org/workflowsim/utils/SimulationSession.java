package org.workflowsim.utils;

import java.util.Calendar;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;

/**
 * Manage the WorkflowSim global state associated with one serial experiment.
 *
 * <p>The bundled CloudSim 3 kernel uses JVM global state, so a session explicitly rejects
 * concurrent use inside the same JVM; every session opened in sequence however obtains a fresh
 * WorkflowSim configuration. Callers should use try-with-resources so that {@link #close()} is
 * invoked.</p>
 */
public final class SimulationSession implements AutoCloseable {

    private static SimulationSession activeSession;

    private final SimulationConfig config;
    private boolean closed;
    private boolean cloudSimInitialized;
    /** True only after this session enters a kernel initialization attempt. */
    private boolean ownsCloudSimKernel;
    /** Global Log switch state at session open, restored by close (R8 audit fix P1-5). */
    private final boolean logDisabledAtOpen;

    private SimulationSession(SimulationConfig config) {
        this.config = config;
        this.logDisabledAtOpen = Log.isDisabled();
    }

    /**
     * Open and configure a new serial WorkflowSim session.
     *
     * @param config immutable configuration of this run
     * @return the activated session
     * @throws IllegalStateException if an unclosed session already exists in the same JVM
     */
    public static synchronized SimulationSession open(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Simulation configuration cannot be null");
        }
        if (activeSession != null) {
            throw new IllegalStateException("A SimulationSession is already active in this JVM");
        }

        Parameters.reset();
        Parameters.setRandomSeed(config.getRandomSeed());
        Parameters.setRuntimeScale(config.getRuntimeScale());
        Parameters.setRuntimeReferenceMips(config.getRuntimeReferenceMips());
        Parameters.setCostModel(config.getCostModel());
        // BAT-D2 (2026-10-29): the dispatch-time frequency tier policy is loaded with the session
        // (same carrier semantics as the scheduling enum).
        Parameters.setFrequencyPolicy(config.getFrequencyPolicy());
        if (config.getRegisteredSchedulingAlgorithmProvider() != null) {
            // BAT-3 (2026-10-26): registered SPI algorithm — carried through the same
            // session-managed legacy Parameters carrier as the scheduling enum; reset()
            // clears it, and the runner validated the self-declared contract beforehand.
            Parameters.setRegisteredSchedulingAlgorithmProvider(config.getRegisteredSchedulingAlgorithmProvider());
        }
        if (config.getWorkflowPaths().size() == 1) {
            Parameters.init(
                    config.getVmCount(),
                    config.getWorkflowPaths().get(0),
                    null,
                    null,
                    config.getOverheadModel().createParameters(),
                    config.getClusteringParameters(),
                    config.getSchedulingAlgorithm(),
                    config.getPlanningAlgorithm(),
                    config.getReduceMethod(),
                    config.getDeadline());
        } else {
            Parameters.init(
                    config.getVmCount(),
                    config.getWorkflowPaths(),
                    null,
                    null,
                    config.getOverheadModel().createParameters(),
                    config.getClusteringParameters(),
                    config.getSchedulingAlgorithm(),
                    config.getPlanningAlgorithm(),
                    config.getReduceMethod(),
                    config.getDeadline());
        }
        Parameters.setWorkflowArrivalSeconds(config.getWorkflowArrivalSeconds());
        ReplicaCatalog.init(config.getFileSystem());
        FailureParameters.reset();
        FailureMonitor.init();
        FailureGenerator.reset();
        config.getFailureModel().applyToFailureParameters(config.getRandomSeed());
        FailureGenerator.init();

        activeSession = new SimulationSession(config);
        return activeSession;
    }

    /**
     * Initialize the CloudSim kernel once all WorkflowSim global state is configured and before any
     * simulation entity is created.
     *
     * <p>After the local argument checks pass, the explicit initialization call hands the kernel
     * lifecycle to this session; an initialization attempt that enters the kernel is cleaned up even
     * when it fails. Merely opening a session, or rejecting arguments before entering the kernel,
     * acquires no ownership of another legacy kernel. Do not replace the kernel from another caller
     * while the session is alive; only serial lifecycles are supported.</p>
     *
     * @param users number of CloudSim users
     * @param calendar simulation start calendar; {@code null} lets CloudSim handle it
     * @param trace whether to enable CloudSim event trace output
     * @throws IllegalStateException if the session is closed, CloudSim is already initialized or
     *         kernel initialization fails
     */
    public void initializeCloudSim(int users, Calendar calendar, boolean trace) {
        requireOpen();
        if (cloudSimInitialized) {
            throw new IllegalStateException("CloudSim is already initialized for this session");
        }
        if (users < 0) {
            throw new IllegalArgumentException("CloudSim user count cannot be negative");
        }
        // Ownership starts after local argument checks, before the first global kernel mutation.
        // Even a failed initialization can leave partial roots that this session must release.
        ownsCloudSimKernel = true;
        try {
            CloudSim.init(users, calendar, trace, config.getCloudSimMinEventIntervalSeconds());
            // The legacy init may log and swallow an initialization exception. A fresh init now
            // clears the old CIS id first, so a missing service cannot masquerade as prior success.
            if (CloudSim.getCloudInfoServiceEntityId() < 0) {
                throw new IllegalStateException("CloudSim kernel initialization failed: "
                        + "Cloud Information Service entity was not registered");
            }
            cloudSimInitialized = true;
        } catch (RuntimeException | Error failure) {
            CloudSim.reset();
            throw failure;
        }
    }

    public SimulationConfig getConfig() {
        return config;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("SimulationSession is already closed");
        }
    }

    /** Attempt every WorkflowSim cleanup even if a preceding cleanup unexpectedly fails. */
    private static void resetWorkflowGlobals() {
        try {
            FailureGenerator.reset();
        } finally {
            try {
                FailureMonitor.reset();
            } finally {
                try {
                    FailureParameters.reset();
                } finally {
                    try {
                        ReplicaCatalog.reset();
                    } finally {
                        Parameters.reset();
                    }
                }
            }
        }
    }

    /**
     * Release session state after serial execution, including failed or unstarted owned kernels.
     *
     * <p>Successful reports must be frozen before closing, as SimulationRunner does. Cleanup
     * invokes no user entity callbacks. A session that never entered kernel initialization
     * leaves an unowned legacy kernel untouched. This is not concurrent cancellation.</p>
     */
    @Override
    public void close() {
        synchronized (SimulationSession.class) {
            if (closed) {
                return;
            }
            try {
                if (ownsCloudSimKernel) {
                    // Do not call finishSimulation: it can run pending user entities and callbacks.
                    CloudSim.reset();
                }
            } finally {
                try {
                    resetWorkflowGlobals();
                } finally {
                    try {
                        Log.setDisabled(logDisabledAtOpen);
                    } finally {
                        closed = true;
                        cloudSimInitialized = false;
                        ownsCloudSimKernel = false;
                        if (activeSession == this) {
                            activeSession = null;
                        }
                    }
                }
            }
        }
    }
}
