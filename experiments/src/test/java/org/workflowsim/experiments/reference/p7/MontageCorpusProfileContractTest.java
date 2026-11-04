package org.workflowsim.experiments.reference.p7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** Guards the clean-checkout versus extended-corpus test boundary (VV-NEW-01, 2026-11-04). */
class MontageCorpusProfileContractTest {

    @Test
    void standardSuiteUsesOnlyTheTrackedMontageInput() throws Exception {
        List<Path> inputs = MontageWfInstancesStructuralTest.montageInputs().collect(Collectors.toList());
        assertEquals(1, inputs.size());
        assertEquals(
                "montage-chameleon-2mass-005d-001.json",
                inputs.get(0).getFileName().toString());
        assertTrue(Files.isRegularFile(inputs.get(0)));
    }
}
