# Golden and Reference-Evidence Registry

This registry distinguishes correctness oracles from drift detectors. It is a navigation and
review contract, not a second set of expected values. The authoritative expected value remains in
its cited paper, contract, invariant, reference implementation, or labeled regression test.

## Legal classes

| Class | Meaning and review rule |
|---|---|
| `PAPER` | A primary-literature table or formula with an independent hand derivation. Record DOI/page and transcription provenance |
| `CONTRACT` | A value derived from the current semantic contract, units, state transitions, or fail-fast rules |
| `INVARIANT` | A conservation, partition, capacity, ordering, or identity law independent of the implementation |
| `REFERENCE_IMPLEMENTATION` | A fixed-commit upstream or independent implementation, restricted to its declared model intersection |
| `GOLDEN_REGRESSION` | A labeled frozen value whose only job is to detect drift. It never proves initial correctness |

Every new entry must name the test, input/protocol identity, metric, expected-value source,
tolerance, coverage, and forbidden extrapolation. A change to an expected value requires a reason
and a linked contract revision, not a silent update to match production output.

## Current anchors

| Evidence | Class | Test and scope |
|---|---|---|
| HEFT ten-task cost/communication example | `PAPER` | `LocalHeftPlanningAlgorithmPaperTableTest`; ranks, first placement, non-overlap, and dependency timing |
| PEFT Figure 1/Tables 5–6 | `PAPER` | `LocalPeftPrimarySourcePaperTest` and its `peft-paper-example.SOURCE.md` provenance record |
| Braun independent-task ECT table | `PAPER` | `StaticIndependentGoldenTableTest`; MCT, MinMin, MaxMin, Sufferage, MET, and OLB hand derivations |
| Shared DAG small-graph reference | `INVARIANT` | `PlanningDagReferencePropertyAuditTest`; independent topological DP, exhaustive paths, and permutation invariance |
| Clustering partition and file conservation | `INVARIANT` | `ClusteringConservationPropertyTest`; no lost/duplicated tasks, length/files, dependency projection, or cycles |
| Energy and carbon accountings | `INVARIANT` | `EnergyConservationLawTest`, `CarbonAccountingTest`, and the independent Python energy/carbon auditors |
| Max-min transfer service | `INVARIANT` | `MaxMinTransferTest`; nominal caps, bottlenecks, duplicate resources, order/scale invariance, and completion boundaries |
| CloudSim 3.0.3 event and Space-Shared semantics | `REFERENCE_IMPLEMENTATION` | `CloudSimKernelReferenceTest` and `CloudletSchedulerReferenceTest`, with fixed commit and M24/M25 |
| Legacy WorkflowSim SHARED semantics | `REFERENCE_IMPLEMENTATION` | `ControlledSharedStorageIndependentReferenceTest`, including the 150 MB/15 MB/s case and M26 |
| Montage and classic input structures | `CONTRACT` | `MontageWfInstancesStructuralTest`, `ClassicDaxStructuralTest`, and `WfCommonsRuntimeReferenceTest` |
| Exact tied-rank Wilcoxon | `INVARIANT` | `PairedWilcoxonExternalReferenceTest`; actual-rank subset-sum, with SciPy when available and stdlib fallback, plus M28 |
| CaWoSched fixed-plan time shifting | `PAPER` / `CONTRACT` | `CaWoSchedPlannerTest`, `CaWoSchedAdapterTest`, `TimedPlanFrameworkTest`, `CommunicationEnhancedPlanTest`, `CaWoSchedExactOptimizerTest`, and `CaWoSchedTimedExecutionIntegrationTest`; all 16 variants, exact DP, timed dispatch, and M32–M35 |
| CWS RankMin-RR | `PAPER` | `CwsDagRankTest`, `CwsRankMinRoundRobinSchedulingAlgorithmTest`, and `CwsFigureOneIntegrationTest`; exact Figure 1 rank/DAG and 5.21→4.21 direction, M36 |
| NACO hybrid GA/HEFT + DNC | `PAPER` / `CONTRACT` | `NacoDncEvaluatorTest`, `NacoGaHeftOptimizerTest`, and `NacoGaHeftIntegrationTest`; hand-derived DNC/mapping and elite-GA checks, M37/M38 |
| Reshi-C/M | `PAPER` | `ReshiSchedulingAlgorithmTest`, `ReshiIntegrationTest`, and `ReshiPredictionErrorModelTest`; explicit rank/history and sign-clamp, M39 |
| GreenHEFT | `PAPER` | `GreenHeftPlannerTest` and `GreenHeftIntegrationTest`; energy-first mapping and declared-power oracle, M40 |
| MOHEFT | `PAPER` / `CONTRACT` | `MoHeftPlannerTest` and `MoHeftIntegrationTest`; dominance, Pareto extremes and balanced selection, M41/M42 |
| IC-PCP | `PAPER` / `CONTRACT` | `IcpcpPlannerTest` and `IcpcpIntegrationTest`; low-cost path, deadline infeasibility, fork/join and cycle, M43/M44 |
| Network-Aware HEFT | `PAPER` | `NetworkHeftPlannerTest` and `NetworkHeftIntegrationTest`; shared-bottleneck stretching and 25→32 direction, M45 |
| EBPSM | `PAPER` / `CONTRACT` | `EbpsmBudgetDistributorTest`, `EbpsmSchedulingAlgorithmTest`, and `EbpsmIntegrationTest`; SFTD, cache preference and budget gate, M46/M47 |
| DVFS slack allocation | `PAPER` / `CONTRACT` | `DvfsSlackOptimizerTest` and `DvfsSlackIntegrationTest`; energy/deadline-gated downscaling and runtime tier consumption, M48 |
| CRCH checkpoint recovery | `PAPER` | `CrchRegistryTest`, `CrchSchedulingAlgorithmTest`, and `CrchIntegrationTest`; saved-work and alternative-VM recovery, M49/M50 |
| Tick and profile semantics | `CONTRACT` / `INVARIANT` | `SimulationTickSensitivityTest` and `DataflowStateProfileSmokeTest`; no numerical-convergence or timing-threshold claim |
| Large-scale and campaign makespans | `GOLDEN_REGRESSION` | `LargeWorkflowScaleRegressionTest` and `FatTreeCampaignGoldenIntegrationTest`; drift detection only, not paper or real-platform proof |
| Frozen standard/power/carbon/DVFS bundles | `GOLDEN_REGRESSION` | Freshly restored tar archives independently audited by `verify-study-evidence.py`; historical protocol identity, not current regeneration or real validation |
| P7 retained numeric results | `GOLDEN_REGRESSION` | `P7_PROTOCOL.md` explicitly marks the old bundles as deleted and the values as historical, not current-code correctness evidence |

## Review and change procedure

1. Prefer a paper, contract, or invariant over a new golden. If a golden is unavoidable, label it
   `GOLDEN_REGRESSION` at the test and in this registry.
2. Preserve original input bytes, source commit, platform/configuration, protocol identity, and
   the reason for any tolerance. Never use the implementation under test to generate the expected
   value silently.
3. Register a semantic canary for a new research-critical path and demonstrate its kill red-first.
   A green test alone is not evidence that the path is mutation-sensitive.
4. Revalidate frozen evidence after restoration; never edit an archive to make a new implementation
   appear compatible. If semantics intentionally change, create a new protocol and leave the old
   evidence's historical boundary intact.
5. Performance evidence additionally needs environment/JVM/heap identity, scale, sampling, semantic
   fingerprints, and allocation/copy counts. Timing is descriptive and must not substitute for
   correctness or prove universal speedup.

See [V&V case and golden-evidence ledger](<VV_CASE_LEDGER.md>) for assessment states and
[application benchmarks](<APPLICATION_BENCHMARKS.md>) for input-population boundaries.
