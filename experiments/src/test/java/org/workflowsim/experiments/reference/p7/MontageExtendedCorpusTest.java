package org.workflowsim.experiments.reference.p7;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Opt-in verification of all 17 locally retained Montage WfInstances inputs. This class is
 * excluded by default and runs only under the explicit `extended-corpus` Maven profile (VV-NEW-01).
 */
class MontageExtendedCorpusTest {

    @BeforeEach
    void quiet() {
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.enable();
    }

    static Stream<Path> extendedMontageInputs() throws Exception {
        Path root = Paths.get(System.getProperty("workflowsim.datasetRoot", "../datasets"))
                .toAbsolutePath()
                .normalize()
                .resolve("wfinstances/v1.5/pegasus/montage");
        List<Path> inputs = new ArrayList<Path>();
        try (Stream<Path> paths = Files.list(root)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(inputs::add);
        }
        if (inputs.size() != 17) {
            throw new IllegalStateException(
                    "The extended-corpus profile requires all 17 Montage inputs; found " + inputs.size());
        }
        return inputs.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extendedMontageInputs")
    void parsesExtendedStructureUnitsAndDeclaredEvidenceBoundary(Path input) throws Exception {
        MontageStructuralTestSupport.verify(input);
    }
}
