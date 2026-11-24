package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

/** FGCS-R5 (2026-11-24): planner speed basis and clock-invariant local EDP. */
class FrequencySpeedConsistencyTest {

    @TempDir
    Path directory;

    /**
     * Nominal MIPS rank VM 1 first (2000 versus 500) but the default tiers rank VM 0 first (2000
     * versus 500), so every nominal-speed decision would pick the 4x slower VM.
     */
    @Test
    void defaultTiersThatReorderTheFleetAreRejected() throws Exception {
        PlatformProfile platform = platform(new double[] {500.0, 2000.0}, new double[][] {{2000.0}, {500.0}});
        SimulationConfigurationException rejection = assertThrows(
                SimulationConfigurationException.class, () -> run(platform, FrequencyPolicy.DEFAULT, oneJob()));
        assertTrue(rejection.getMessage().contains("same ratio"), rejection.getMessage());
    }

    /** A uniform turbo factor (2x on every VM) keeps the nominal order and stays legal. */
    @Test
    void uniformTurboRatioIsAccepted() throws Exception {
        PlatformProfile platform = platform(new double[] {500.0, 1000.0}, new double[][] {{1000.0}, {2000.0}});
        assertTrue(run(platform, FrequencyPolicy.DEFAULT, oneJob()).getMetrics().isWorkflowCompletedSuccessfully());
    }

    /**
     * Forty identical 1000 MI tasks in a chain on one VM. Local EDP is {@code Pmax * T^2}: 2000 MIPS
     * at 1000 W gives 1000 * 0.25 = 250, 1000 MIPS at 200 W gives 200 * 1 = 200, so every task takes
     * 1000 MIPS no matter when it is dispatched.
     */
    @Test
    void localEdpChoiceDoesNotDependOnTheDispatchClock() throws Exception {
        StringBuilder chain = new StringBuilder();
        for (int id = 1; id <= 40; id++) {
            chain.append("<job id=\"j").append(id).append("\" name=\"j\" runtime=\"1\"/>");
        }
        for (int id = 2; id <= 40; id++) {
            chain.append("<child ref=\"j")
                    .append(id)
                    .append("\"><parent ref=\"j")
                    .append(id - 1)
                    .append("\"/></child>");
        }
        PlatformProfile platform = platform(
                new double[] {2000.0},
                null,
                Arrays.asList(
                        new PlatformProfile.FrequencyTier(2000.0, 50.0, 1000.0),
                        new PlatformProfile.FrequencyTier(1000.0, 25.0, 200.0)));
        SimulationReport report = run(platform, FrequencyPolicy.EDP_HEURISTIC, chain.toString());
        int compute = 0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getTierMips() != null) {
                assertEquals(1000.0, job.getTierMips().doubleValue(), 0.0, "start " + job.getStartTime());
                compute++;
            }
        }
        assertEquals(40, compute);
    }

    private static String oneJob() {
        return "<job id=\"a\" name=\"a\" runtime=\"4\"/>";
    }

    private SimulationReport run(PlatformProfile platform, FrequencyPolicy policy, String body) throws Exception {
        Path dax = directory.resolve("w.dax");
        Files.write(
                dax,
                ("<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\" version=\"2.1\">" + body + "</adag>")
                        .getBytes(StandardCharsets.UTF_8));
        return new SimulationRunner()
                .run(
                        SimulationConfig.builder(
                                        dax.toString(), platform.getVms().size())
                                .schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MINMIN)
                                .frequencyPolicy(policy)
                                .runtimeReferenceMips(1000.0)
                                .build(),
                        platform);
    }

    private static PlatformProfile platform(double[] mips, double[][] tierMips) {
        return platform(mips, tierMips, null);
    }

    private static PlatformProfile platform(
            double[] mips, double[][] tierMips, List<PlatformProfile.FrequencyTier> tiers) {
        PlatformProfile.Builder builder =
                PlatformProfile.builder("tier-speed").storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20));
        for (int id = 0; id < mips.length; id++) {
            List<PlatformProfile.FrequencyTier> own = tiers != null
                    ? tiers
                    : Arrays.asList(new PlatformProfile.FrequencyTier(tierMips[id][0], 50.0, 200.0));
            builder.addHost(new PlatformProfile.HostSpec(id, 1, 4000.0, 1024, 1000L, 1_000_000L))
                    .addVm(new PlatformProfile.VmSpec(
                            id,
                            mips[id],
                            1,
                            512,
                            1000L,
                            10_000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            0.0,
                            0.0,
                            null,
                            own));
        }
        return builder.build();
    }
}
