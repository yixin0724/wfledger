package org.workflowsim.experiments.rerun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

final class RerunTestSupport {

    static final String FIXTURE_RUN_ID = "result";

    private RerunTestSupport() {}

    static Path generateEvidence(Path directory) throws Exception {
        String dax = resourcePath("/dax/reproducibility-workflow.dax");
        SimulationConfig config = SimulationConfig.builder(dax, 3)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .randomSeed(91L)
                .build();
        return generateEvidence(directory, config, PlatformProfiles.homogeneousLocal("rerun-fixture", 3));
    }

    static Path generateEvidence(Path directory, SimulationConfig config, PlatformProfile platform) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("Fixture simulation did not complete all tasks");
        }
        ExperimentArtifactWriter.write(report, directory, FIXTURE_RUN_ID);
        return directory;
    }

    static String resourcePath(String resource) throws Exception {
        return Paths.get(RerunTestSupport.class.getResource(resource).toURI()).toString();
    }

    static void copyEvidenceFiles(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (java.util.stream.Stream<Path> stream = Files.list(source)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
    }
}
