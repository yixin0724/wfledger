package org.workflowsim.experiments.registry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiment.registry.AlgorithmDeclaration;
import org.workflowsim.experiment.registry.AlgorithmRegistry;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class GradStudentSchedulerExerciseIntegrationTest {

    private boolean logWasDisabled;

    @TempDir
    Path tempDir;

    @BeforeEach
    void disableSimulationLog() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLog() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void novelRegistryAlgorithmRunsAndProducesValidatedEvidence() throws Exception {

        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(GradStudentBwFirstSchedulerProvider.LABEL);
        org.junit.jupiter.api.Assertions.assertSame(
                GradStudentBwFirstSchedulerProvider.class,
                provider.getClass(),
                "the ServiceLoader must discover the test-tree provider");

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        GradStudentBwFirstSchedulerProvider.LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.SHARED,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                new org.workflowsim.failure.FailureModelConfig.Builder().build(),
                                null)));

        Path workflow = exerciseDax();
        PlatformProfile platform = exercisePlatform();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .registeredSchedulingAlgorithm(provider)
                .randomSeed(20261026L)
                .build();
        SimulationReport report = new SimulationRunner().run(config, platform);
        org.junit.jupiter.api.Assertions.assertTrue(
                report.getMetrics().isAllLogicalTasksCompletedSuccessfully(),
                "the registered algorithm must complete the exercise workflow");
        org.junit.jupiter.api.Assertions.assertEquals(4, report.getTasks().size(), "the exercise DAG has four tasks");

        Path output = tempDir.resolve("grad-student-evidence");
        String runId = "grad-bw-first-s20261026";
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report, output, runId);
        ExperimentArtifactValidator.ValidationResult result =
                ExperimentArtifactValidator.validate(artifacts.getManifest());
        org.junit.jupiter.api.Assertions.assertNotNull(result.getEvents(), "bundle must contain an events sidecar");

        com.google.gson.JsonObject manifest = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                        java.nio.file.Files.newInputStream(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        org.junit.jupiter.api.Assertions.assertEquals(
                "INVALID",
                manifest.getAsJsonObject("configuration")
                        .get("schedulingAlgorithm")
                        .getAsString());
        org.junit.jupiter.api.Assertions.assertTrue(
                manifest.getAsJsonObject("configuration").has("registeredSchedulingAlgorithm"),
                "the manifest must record the registered algorithm label");
        org.junit.jupiter.api.Assertions.assertEquals(
                GradStudentBwFirstSchedulerProvider.LABEL,
                manifest.getAsJsonObject("configuration")
                        .get("registeredSchedulingAlgorithm")
                        .getAsString());
    }

    static final class GradStudentBwFirstSchedulingAlgorithm extends BaseSchedulingAlgorithm {

        @Override
        public void run() {
            validateCloudletCompatibility();
            List<CondorVM> vms = sortedVmsById();
            for (Iterator it = getCloudletList().iterator(); it.hasNext(); ) {
                Cloudlet cloudlet = (Cloudlet) it.next();
                CondorVM best = null;
                for (CondorVM vm : vms) {
                    if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                        if (best == null || vm.getBw() > best.getBw()) {
                            best = vm;
                        }
                    }
                }

                if (best == null) {
                    break;
                }
                best.setState(WorkflowSimTags.VM_STATUS_BUSY);
                cloudlet.setVmId(best.getId());
                getScheduledList().add(cloudlet);
            }
        }
    }

    public static final class GradStudentBwFirstSchedulerProvider implements SchedulingAlgorithmProvider {

        static final String LABEL = "GRAD_BW_FIRST";

        @Override
        public String label() {
            return LABEL;
        }

        @Override
        public BaseSchedulingAlgorithm create() {
            return new GradStudentBwFirstSchedulingAlgorithm();
        }

        @Override
        public AlgorithmDeclaration declaration() {
            return new AlgorithmDeclaration.Builder()
                    .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                    .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                    .acceptsTaskCostMatrix(false)
                    .allowsFailureModel(false)
                    .description("Grad-student exercise: prefer the idle compatible VM with the largest bandwidth")
                    .build();
        }
    }

    private Path exerciseDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"4\">\n");
        dax.append("  <job id=\"t1\" name=\"a\" runtime=\"60\">\n");
        dax.append("    <uses name=\"out-1.bin\" link=\"output\" size=\"8000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t2\" name=\"b\" runtime=\"50\">\n");
        dax.append("    <uses name=\"out-2.bin\" link=\"output\" size=\"6000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t3\" name=\"c\" runtime=\"40\">\n");
        dax.append("    <uses name=\"out-1.bin\" link=\"input\" size=\"8000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t4\" name=\"d\" runtime=\"30\">\n");
        dax.append("    <uses name=\"out-2.bin\" link=\"input\" size=\"6000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <child ref=\"t3\"><parent ref=\"t1\"/></child>\n");
        dax.append("  <child ref=\"t4\"><parent ref=\"t2\"/></child>\n");
        dax.append("</adag>\n");
        Path path = Paths.get(tempDir.toFile().getAbsolutePath(), "grad-exercise.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static PlatformProfile exercisePlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("grad-exercise-platform");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                0, 1000.0, 1, 512, 900L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                1, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        return builder.build();
    }
}
