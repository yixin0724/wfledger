# Cross-Paper Comparison Scenes

## Purpose and scope

This report compares compatible algorithm subsets through the real `SimulationRunner`. SHARED and
LOCAL scenes are separate physical models; their values are not merged into one population. The
comparison is a controlled two-task DAG, not a claim about general workflow performance.

`CrossPaperComparisonTest` constructs the same two-task chain with 100 bytes of data,
`runtimeReferenceMips=1.0`, `randomSeed=7`, two VMs, declared VM costs, and declared power. SHARED
uses `legacyWorkflowsimV1`, while LOCAL uses `preExecutionTransferDelayV1`. Every run must complete
successfully and produce non-negative energy and modeled processing cost.

## R-COMPARE-02 audit correction

The first report used a scene whose power/runtime ratios tied energy and whose LOCAL bandwidth was
too large to distinguish DHEFT. It also displayed runtime modeled processing cost beside an
ambiguous planner-cost label. The current scene uses heterogeneous power and pairwise bandwidth;
it emits task-to-VM mappings, independently recomputes each VM's flat-power energy integral with a
`<1e-6 J` residual, and attaches IC-PCP planner cost directly from `IcpcpPlanningAlgorithm`.

**Identical metrics are expected when two algorithms choose the same task-to-VM mapping.** They are
not evidence that the algorithms are equivalent. Modeled processing cost is the runtime Job-envelope
accounting; IC-PCP planner cost is the path-resource window cost from the planner schedule. They are
not interchangeable.

## SHARED scene

| Algorithm | Task 1 → VM | Task 2 → VM | Makespan (s) | Total energy (J) | Modeled processing cost | IC-PCP planner cost |
|---|---:|---:|---:|---:|---:|---:|
| `SHARED_STORAGE_HEFT` | 3 | 3 | 2.21 | 434.05 | 6.33002 | — |
| `NETWORK_HEFT` | 3 | 3 | 2.21 | 434.05 | 6.33002 | — |
| `GREEN_HEFT` | 8 | 8 | 4.21 | 264.05 | 12.33002 | — |
| `MO_HEFT` | 3 | 8 | 3.21 | 349.05 | 9.33002 | — |
| `IC_PCP` | 8 | 8 | 4.21 | 264.05 | 12.33002 | 4.0 |

HEFT and Network-Aware HEFT choose the same mapping in this small scene. GreenHEFT and IC-PCP also
choose the same mapping but optimize different objectives; MOHEFT chooses a mixed mapping. The test
requires at least three distinct mappings so a degenerate all-identical comparison cannot pass.

## LOCAL scene

| Algorithm | Task 1 → VM | Task 2 → VM | Makespan (s) | Total energy (J) | Modeled processing cost |
|---|---:|---:|---:|---:|---:|
| `LOCAL_HEFT` | 3 | 3 | 2.21 | 434.05 | 6.33002 |
| `DHEFT` | 3 | 3 | 2.21 | 434.05 | 6.33002 |

This LOCAL scene is **non-discriminating**: both algorithms select the same mapping, so their
metrics are necessarily identical. It is not used to claim algorithm equivalence. DHEFT's pairwise
minimum-bandwidth and parent-finish semantics are verified separately by `DheftPlannerTest` and
M51/M52.

## Reproduction command

```bash
mvn -pl :workflowsim-workbench -am test-compile
mvn -pl :workflowsim-workbench -Dtest=CrossPaperComparisonTest test
```

The comparison uses the production class
`experiments/src/main/java/org/workflowsim/experiments/comparison/CrossPaperComparison.java`.
