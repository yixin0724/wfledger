package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Guards contract documentation against reintroducing contradictions found in the 2026-11-04
 * V&V design review. Expectations come from the current implementation contracts and retained
 * evidence layout, not from generated simulation output.
 */
class DocumentationConsistencyTest {

    @Test
    void carbonDesignUsesTheImplementedIntensityUnitLaw() throws Exception {
        String document = read("docs/advanced/CARBON_DIMENSION_DESIGN.md");
        assertTrue(document.contains("E_vm[J] / 3.6e6 × intensity_vm[g/kWh]"));
        assertFalse(document.contains("3.6 × E_vm(J) / 3600"));
    }

    @Test
    void carbonDesignUsesTheDeclaredAccountingHorizon() throws Exception {
        String document = read("docs/advanced/CARBON_DIMENSION_DESIGN.md").replaceAll("\\s+", " ");
        assertTrue(document.contains("outside the declared windows is not included in the excess integral"));
        assertFalse(document.contains("cover [0, T_sim) exactly"));
    }

    @Test
    void standardBenchmarkRestoresArchivesBeforeAuditing() throws Exception {
        String document = read("docs/benchmark/STANDARD_BENCHMARK.md");
        assertTrue(document.contains("tar -xzf output/benchmark-standard-v1-evidence.tar.gz -C"));
        for (String rawDirectory : new String[] {
            "output/benchmark-standard-v1/",
            "output/benchmark-standard-power-v1/",
            "output/benchmark-standard-carbon-v1/",
            "output/benchmark-standard-dvfs-v1/"
        }) {
            assertFalse(document.contains(rawDirectory), "stale raw-evidence path: " + rawDirectory);
        }
    }

    @Test
    void dvfsDesignUsesTheCurrentTierRerunContract() throws Exception {
        String document = read("docs/advanced/DVFS_DIMENSION_DESIGN.md");
        assertTrue(document.contains("Superseded by A1 (2026-11-02)"));
        assertFalse(document.contains("still rebuilds neither"));
        assertFalse(document.contains("remain honestly non-rerunnable"));
    }

    @Test
    void maintainedGuidesUseCurrentModelCountAndEditorialConventions() throws Exception {
        assertTrue(read("AGENTS.md").contains("Nine data/network physical models"));
        String portrait = read("docs/benchmark/ENERGY_PORTRAIT.md");
        assertTrue(portrait.startsWith("# Energy Portrait of the Algorithm Corpus"));
        assertFalse(portrait.contains("The The platform"));
        assertTrue(read("docs/getting-started/BUILD.md").contains("about 5–10 minutes"));
        assertTrue(read("Makefile").contains("about 5-10 minutes"));
    }

    @Test
    void vvCaseLedgerDefinesEvidenceClassesAndProhibitsAggregateScores() throws Exception {
        String document = read("docs/experiments/VV_CASE_LEDGER.md");
        for (String evidenceClass :
                new String[] {"PAPER", "CONTRACT", "INVARIANT", "REFERENCE_IMPLEMENTATION", "GOLDEN_REGRESSION"}) {
            assertTrue(document.contains(evidenceClass), "missing evidence class " + evidenceClass);
        }
        assertTrue(document.contains("CAPABILITY"));
        assertTrue(document.contains("RESULTS"));
        assertTrue(document.contains("NOT_ESTABLISHED"));
        assertTrue(document.contains("does not produce a single credibility score"));
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get("..").resolve(relative);
        assertTrue(Files.isRegularFile(path), "missing documentation file: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
