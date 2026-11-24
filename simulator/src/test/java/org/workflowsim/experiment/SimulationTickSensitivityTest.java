package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

class SimulationTickSensitivityTest {

    @TempDir
    Path directory;

    static Stream<Arguments> tickCases() {
        return Stream.of(Arguments.of(0.1, 1.21), Arguments.of(0.05, 1.16), Arguments.of(0.025, 1.135));
    }

    @ParameterizedTest(name = "tick {0} seconds")
    @MethodSource("tickCases")
    void oneTaskMakespanFollowsDeclaredTick(double tick, double expectedMakespan) throws Exception {
        Path input = directory.resolve("tick.dax");
        Files.write(
                input,
                ("<adag version=\"3.3\" count=\"1\"><job id=\"a\" name=\"a\" runtime=\"1\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                .cloudSimMinEventIntervalSeconds(tick)
                .build();
        SimulationReport report = new SimulationRunner().run(config, platform());
        assertEquals(expectedMakespan, report.getMakespan(), 1.0e-12);
        assertEquals(
                expectedMakespan,
                report.getMetrics().getControlledSharedStorageCriticalPathLowerBoundSeconds(),
                1.0e-12);
    }

    /**
     * FGCS-R3 (2026-11-24, A4): the completion safety margin scales with the configured kernel
     * tick ({@code safety = tick / 10}, exactly 0.01 s at the 0.1 s default) instead of a fixed
     * 0.01 s. Hand derivation for tick 0.001 s on one 20,000-MIPS VM, chain a -> b, each task
     * 100 MI (0.1 s x 1000 reference MIPS), i.e. 0.005 s of compute; the 110-MI stage-in takes
     * 0.0055 s. Per the CONTRACTS stage-in rule, roots start at
     * {@code max(0.0055, 0 + tick + safety) + tick = 0.0055 + 0.001 = 0.0065}; each task then
     * completes after {@code max(0.005, tick + safety) = max(0.005, 0.0011) = 0.005}. Makespan
     * = 0.0065 + 0.005 + 0.005 = 0.0165 s. The former fixed margin forced every completion to
     * at least 0.011 s and gave 0.012 + 0.011 + 0.011 = 0.034 s.
     */
    @org.junit.jupiter.api.Test
    void completionSafetyMarginScalesWithTheConfiguredTick() throws Exception {
        Path input = directory.resolve("tiny-chain.dax");
        Files.write(
                input,
                ("<adag version=\"3.3\" count=\"2\"><job id=\"a\" name=\"a\" runtime=\"0.1\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"0.1\"/>"
                                + "<child ref=\"b\"><parent ref=\"a\"/></child></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                .cloudSimMinEventIntervalSeconds(0.001)
                .build();
        PlatformProfile fast = PlatformProfile.builder("tick-safety")
                .addHost(new PlatformProfile.HostSpec(0, 1, 20_000.0, 1024, 1000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(
                        0, 20_000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();
        SimulationReport report = new SimulationRunner().run(config, fast);
        assertEquals(0.0165, report.getMakespan(), 1.0e-12);
        assertEquals(0.0165, report.getMetrics().getControlledSharedStorageCriticalPathLowerBoundSeconds(), 1.0e-12);
    }

    /** FGCS-R3 (2026-11-24, A4): the derived defaults are bit-identical to the former literals. */
    @org.junit.jupiter.api.Test
    void derivedTimingConstantsAreBitIdenticalAtTheDefaultTick() {
        assertEquals(0, Double.compare(0.01, org.workflowsim.utils.SimulationTiming.completionSafetySeconds(0.1)));
        assertEquals(
                0,
                Double.compare(0.111, org.workflowsim.utils.SimulationTiming.initialProcessingThresholdSeconds(0.1)));
    }

    private static PlatformProfile platform() {
        return PlatformProfile.builder("tick-sensitivity")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(
                        0, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();
    }
}
