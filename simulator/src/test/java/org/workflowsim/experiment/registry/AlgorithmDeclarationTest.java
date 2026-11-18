package org.workflowsim.experiment.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.ReplicaCatalog.FileSystem;

class AlgorithmDeclarationTest {

    @Test
    void anyScopeCoversBothSupportedFileSystems() {
        AlgorithmDeclaration declaration = new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                .description("any")
                .build();
        assertTrue(declaration.coversFileSystem(FileSystem.LOCAL), "ANY must cover LOCAL");
        assertTrue(declaration.coversFileSystem(FileSystem.SHARED), "ANY must cover SHARED");
        assertTrue(declaration.coversFileSystem(ReplicaCatalog.FileSystem.LOCAL));
    }

    @Test
    void localOnlyScopeCoversOnlyLocalAndNullIsNeverCovered() {
        AlgorithmDeclaration declaration = new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.LOCAL_ONLY)
                .description("local")
                .build();
        assertTrue(declaration.coversFileSystem(FileSystem.LOCAL));
        assertFalse(declaration.coversFileSystem(FileSystem.SHARED), "LOCAL_ONLY must not cover SHARED");
        assertFalse(declaration.coversFileSystem(null), "null is never covered");
    }

    @Test
    void sharedOnlyScopeCoversOnlyShared() {
        AlgorithmDeclaration declaration = new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.SHARED_ONLY)
                .description("shared")
                .build();
        assertTrue(declaration.coversFileSystem(FileSystem.SHARED));
        assertFalse(declaration.coversFileSystem(FileSystem.LOCAL), "SHARED_ONLY must not cover LOCAL");
        assertFalse(declaration.coversFileSystem(null));
    }

    @Test
    void declarationMetadataIsImmutableAndValidated() {
        AlgorithmDeclaration declaration = new AlgorithmDeclaration.Builder()
                .acceptsTaskCostMatrix(true)
                .allowsFailureModel(false)
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .description("matrix-friendly")
                .build();
        assertTrue(declaration.acceptsTaskCostMatrix());
        assertFalse(declaration.allowsFailureModel());
        assertTrue(declaration.getAllowedDataMovementKinds().contains("LEGACY_WORKFLOWSIM_V1"));
        assertFalse(declaration.getFileSystemScope() == null);
    }

    @Test
    void energyFieldsDefaultOffAndImplicationsAreEnforced() {

        AlgorithmDeclaration plain = new AlgorithmDeclaration.Builder()
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .build();
        assertFalse(plain.isEnergyAware());
        assertFalse(plain.isPowerModelRequired());
        assertTrue(plain.getEnergyObjectiveWeight() == 0.0);

        AlgorithmDeclaration required =
                new AlgorithmDeclaration.Builder().powerModelRequired(true).build();
        assertTrue(required.isPowerModelRequired());
        assertTrue(required.isEnergyAware());

        AlgorithmDeclaration weighted =
                new AlgorithmDeclaration.Builder().energyObjectiveWeight(1.5).build();
        assertTrue(weighted.isEnergyAware());
        assertTrue(weighted.getEnergyObjectiveWeight() == 1.5);
    }

    @Test
    void energyWeightMustBeFiniteNonNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmDeclaration.Builder()
                        .energyObjectiveWeight(-0.1)
                        .build(),
                "negative weight must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmDeclaration.Builder()
                        .energyObjectiveWeight(Double.POSITIVE_INFINITY)
                        .build(),
                "infinite weight must be rejected");
    }

    @Test
    void carbonFieldsDefaultOffAndImplicationsAreEnforced() {

        AlgorithmDeclaration plain = new AlgorithmDeclaration.Builder()
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .build();
        assertFalse(plain.isCarbonAware());
        assertTrue(plain.getCarbonObjectiveWeight() == 0.0);
        assertFalse(plain.isEnergyAware());

        AlgorithmDeclaration carbonAwareOnly =
                new AlgorithmDeclaration.Builder().carbonAware(true).build();
        assertTrue(carbonAwareOnly.isCarbonAware());
        assertFalse(carbonAwareOnly.isEnergyAware(), "carbonAware alone does not imply energyAware");

        AlgorithmDeclaration weighted =
                new AlgorithmDeclaration.Builder().carbonObjectiveWeight(1.0).build();
        assertTrue(weighted.isCarbonAware());
        assertTrue(weighted.isEnergyAware(), "positive carbon weight rides the energy chain");
        assertTrue(weighted.getCarbonObjectiveWeight() == 1.0);

        AlgorithmDeclaration demonstrator = new AlgorithmDeclaration.Builder()
                .powerModelRequired(true)
                .carbonObjectiveWeight(1.0)
                .build();
        assertTrue(demonstrator.isPowerModelRequired());
        assertTrue(demonstrator.isEnergyAware());
        assertTrue(demonstrator.isCarbonAware());
    }

    @Test
    void carbonWeightMustBeFiniteNonNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmDeclaration.Builder()
                        .carbonObjectiveWeight(-0.1)
                        .build(),
                "negative carbon weight must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlgorithmDeclaration.Builder()
                        .carbonObjectiveWeight(Double.POSITIVE_INFINITY)
                        .build(),
                "infinite carbon weight must be rejected");
    }

    @Test
    void registryRejectsPowerRequiredAlgorithmOnUnpoweredPlatform() {

        IllegalArgumentException unpowered = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        "ENERGY-TEST-REQUIRED",
                        new AlgorithmRegistry.ValidationInput(
                                FileSystem.SHARED,
                                null,
                                false,
                                null,
                                org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID,
                                false)),
                "power-required algorithm must be rejected on unpowered platform");
        assertTrue(
                unpowered.getMessage().contains("powerModelRequired"),
                "message must cite the power clause: " + unpowered.getMessage());

        AlgorithmRegistry.validate(
                "ENERGY-TEST-REQUIRED",
                new AlgorithmRegistry.ValidationInput(
                        FileSystem.SHARED,
                        null,
                        false,
                        null,
                        org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID,
                        true));
    }

    @Test
    void dvfsFieldsDefaultOffAndImplicationLatticeIsEnforced() {

        AlgorithmDeclaration plain = new AlgorithmDeclaration.Builder()
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .build();
        assertFalse(plain.isDvfsAware());
        assertFalse(plain.isFrequencyTiersRequired());

        AlgorithmDeclaration required =
                new AlgorithmDeclaration.Builder().frequencyTiersRequired(true).build();
        assertTrue(required.isFrequencyTiersRequired());
        assertTrue(required.isDvfsAware(), "frequencyTiersRequired must pull dvfsAware up");

        AlgorithmDeclaration awareOnly =
                new AlgorithmDeclaration.Builder().dvfsAware(true).build();
        assertTrue(awareOnly.isDvfsAware());
        assertFalse(awareOnly.isFrequencyTiersRequired());

        AlgorithmDeclaration demonstrator = new AlgorithmDeclaration.Builder()
                .powerModelRequired(true)
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .build();
        assertTrue(demonstrator.isDvfsAware());
        assertTrue(demonstrator.isFrequencyTiersRequired());
        assertTrue(demonstrator.isEnergyAware());
    }

    @Test
    void registryRejectsFrequencyTiersRequiredAlgorithmOnTierlessPlatform() {

        IllegalArgumentException tierless = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        "DVFS-TEST-REQUIRED",
                        new AlgorithmRegistry.ValidationInput(
                                FileSystem.SHARED,
                                null,
                                false,
                                null,
                                org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID,
                                false,
                                false)),
                "frequency-tiers-required algorithm must be rejected on a tierless platform");
        assertTrue(
                tierless.getMessage().contains("frequencyTiersRequired")
                        && tierless.getMessage().contains("frequencyTiers"),
                "message must cite the tier clause and the platform field face: " + tierless.getMessage());

        AlgorithmRegistry.validate(
                "DVFS-TEST-REQUIRED",
                new AlgorithmRegistry.ValidationInput(
                        FileSystem.SHARED,
                        null,
                        false,
                        null,
                        org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID,
                        false,
                        true));
    }

    @Test
    void legacyValidationInputConstructorsDefaultTiersToFalse() throws Exception {

        org.workflowsim.utils.SimulationConfig config =
                org.workflowsim.utils.SimulationConfig.builder("legacy.dax", 1).build();
        AlgorithmRegistry.ValidationInput fromConfig = new AlgorithmRegistry.ValidationInput(config);
        assertTrue(
                !fromConfig.isPlatformFrequencyTiersDeclared(),
                "SimulationConfig-based assembly defaults the tier fact to false");
        AlgorithmRegistry.ValidationInput legacyFive = new AlgorithmRegistry.ValidationInput(
                FileSystem.SHARED, null, false, null, org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID);
        assertTrue(!legacyFive.isPlatformFrequencyTiersDeclared());
        AlgorithmRegistry.ValidationInput legacySix = new AlgorithmRegistry.ValidationInput(
                FileSystem.SHARED, null, false, null, org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID, true);
        assertTrue(
                legacySix.isPlatformPowerDeclared() && !legacySix.isPlatformFrequencyTiersDeclared(),
                "6-arg legacy keeps power fact, tiers default false");

        assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate("DVFS-TEST-REQUIRED", legacyFive),
                "legacy input faces must not bypass the tier gate");
    }
}
