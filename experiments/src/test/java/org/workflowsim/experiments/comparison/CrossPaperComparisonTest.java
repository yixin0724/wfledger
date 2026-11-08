package org.workflowsim.experiments.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfile;

/** Real cross-paper comparison with separate SHARED and LOCAL physical models. */
class CrossPaperComparisonTest {

    @TempDir
    Path directory;

    @Test
    void sharedAndLocalScenesProduceAuditableResults() throws Exception {
        Path workflow = directory.resolve("comparison.dax");
        Files.write(
                workflow,
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                + "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">\n"
                                + "  <job id=\"a\" name=\"a\" runtime=\"1000\">\n"
                                + "    <uses file=\"x\" link=\"output\" size=\"100\"/>\n"
                                + "  </job>\n"
                                + "  <job id=\"b\" name=\"b\" runtime=\"1000\">\n"
                                + "    <uses file=\"x\" link=\"input\" size=\"100\"/>\n"
                                + "  </job>\n"
                                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                                + "</adag>\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PlatformProfile platform = PlatformProfile.builder("comparison")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        3,
                        1000.0,
                        1,
                        4096,
                        10000L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(10.0, 0.0, 0.0, 0.0),
                        10.0,
                        100.0,
                        null,
                        java.util.Collections.emptyList()))
                .addVm(new PlatformProfile.VmSpec(
                        8,
                        500.0,
                        1,
                        4096,
                        10000L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(1.0, 0.0, 0.0, 0.0),
                        5.0,
                        50.0,
                        null,
                        java.util.Collections.emptyList()))
                .build();

        List<CrossPaperComparison.RunResult> shared = CrossPaperComparison.runSharedScene(workflow, platform);
        List<CrossPaperComparison.RunResult> local = CrossPaperComparison.runLocalScene(workflow, platform);

        assertEquals(5, shared.size());
        assertEquals(2, local.size());
        for (CrossPaperComparison.RunResult result : shared) {
            assertTrue(result.isCompleted(), result.getAlgorithm());
            assertTrue(result.getMakespanSeconds() > 0.0, result.getAlgorithm());
            assertTrue(result.getEnergyJoules() >= 0.0, result.getAlgorithm());
            assertTrue(result.getModeledProcessingCost() >= 0.0, result.getAlgorithm());
            System.out.println("SHARED " + result.getAlgorithm() + " makespan=" + result.getMakespanSeconds()
                    + " energy=" + result.getEnergyJoules() + " cost=" + result.getModeledProcessingCost());
        }
        for (CrossPaperComparison.RunResult result : local) {
            assertTrue(result.isCompleted(), result.getAlgorithm());
            assertTrue(result.getMakespanSeconds() > 0.0, result.getAlgorithm());
            System.out.println("LOCAL " + result.getAlgorithm() + " makespan=" + result.getMakespanSeconds()
                    + " energy=" + result.getEnergyJoules() + " cost=" + result.getModeledProcessingCost());
        }
    }
}
