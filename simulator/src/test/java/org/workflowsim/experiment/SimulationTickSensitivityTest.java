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

/** Hand-derived tick semantics, not a numerical-convergence claim (B4, 2026-11-04). */
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

    private static PlatformProfile platform() {
        return PlatformProfile.builder("tick-sensitivity")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(
                        0, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();
    }
}
