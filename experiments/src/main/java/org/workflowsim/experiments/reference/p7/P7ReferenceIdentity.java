package org.workflowsim.experiments.reference.p7;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.workflowsim.experiment.ExperimentEvidenceContext;

final class P7ReferenceIdentity {

    static final String STUDY_ID = "p7-baseline";
    static final String GROUP_ID = "org.workflowsim";
    static final String ARTIFACT_ID = "workflowsim-experiments";
    static final String VERSION = "1.0";
    static final String PROTOCOL_FILE_NAME = "P7_PROTOCOL.md";
    static final String PROTOCOL_ID = "docs/experiments/reference-baselines/" + PROTOCOL_FILE_NAME;
    static final String SOURCE_SUBPATH = "org/workflowsim/experiments/reference/p7";

    private P7ReferenceIdentity() {}

    static ExperimentEvidenceContext context(Path datasetRoot) {
        Path normalizedRoot = ReferenceDatasetRoot.require(datasetRoot);
        return ExperimentEvidenceContext.builder(STUDY_ID)
                .artifact(GROUP_ID, ARTIFACT_ID, VERSION)
                .driver(P7BaselineExecutor.class, SOURCE_SUBPATH)
                .protocol(PROTOCOL_ID, protocolFile())
                .datasetRoot(normalizedRoot)
                .build();
    }

    private static Path protocolFile() {
        try {
            URL codeSource = P7BaselineExecutor.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
            if (codeSource == null) {
                return null;
            }
            Path classes = Paths.get(codeSource.toURI()).toAbsolutePath().normalize();
            if (!Files.isDirectory(classes)
                    || classes.getFileName() == null
                    || !"classes".equals(classes.getFileName().toString())) {
                return null;
            }
            Path target = classes.getParent();
            if (target == null
                    || target.getFileName() == null
                    || !"target".equals(target.getFileName().toString())) {
                return null;
            }
            Path experimentModule = target.getParent();
            if (experimentModule == null || !Files.isRegularFile(experimentModule.resolve("pom.xml"))) {
                return null;
            }
            Path projectRoot = experimentModule.getParent();
            Path candidate = projectRoot == null
                    ? null
                    : projectRoot.resolve(PROTOCOL_ID).normalize();
            return candidate != null && Files.isRegularFile(candidate) ? candidate : null;
        } catch (SecurityException | URISyntaxException | IllegalArgumentException exception) {
            return null;
        }
    }
}
