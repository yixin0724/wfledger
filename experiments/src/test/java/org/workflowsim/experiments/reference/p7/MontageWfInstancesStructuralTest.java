package org.workflowsim.experiments.reference.p7;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Standard-checkout verification of the one tracked Montage WfInstances input (B05). */
class MontageWfInstancesStructuralTest {

    @BeforeEach
    void quiet() {
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.enable();
    }

    static Stream<Path> montageInputs() {
        return Stream.of(Paths.get(
                        System.getProperty("workflowsim.datasetRoot", "../datasets"),
                        "wfinstances",
                        "v1.5",
                        "pegasus",
                        "montage",
                        "montage-chameleon-2mass-005d-001.json")
                .toAbsolutePath()
                .normalize());
    }

    @Test
    void parsesTrackedStructureUnitsAndDeclaredEvidenceBoundary() throws Exception {
        MontageStructuralTestSupport.verify(montageInputs()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tracked Montage input is missing")));
    }
}
