# Cross-Paper Comparison Scenes

## Purpose and scope

This report compares compatible algorithm subsets through the real `SimulationRunner`. SHARED and
LOCAL scenes are separate physical models; their values are not merged into one population. The
comparison is a controlled two-task DAG, not a claim about general workflow performance.

`CrossPaperComparisonTest` constructs the same two-task chain with 100 bytes of data,
`runtimeReferenceMips=1.0`, `randomSeed=7`, two VMs, declared VM costs, and declared power. SHARED
uses `legacyWorkflowsimV1`, while LOCAL uses `preExecutionTransferDelayV1`. Every run must complete
successfully and produce non-negative energy and modeled processing cost.

## SHARED scene

| Algorithm | Makespan (s) | Total energy (J) | Modeled processing cost |
|---|---:|---:|---:|
| `SHARED_STORAGE_HEFT` | 2.21 | 223.05 | 6.33002 |
| `NETWORK_HEFT` | 2.21 | 223.05 | 6.33002 |
| `GREEN_HEFT` | 2.21 | 223.05 | 6.33002 |
| `MO_HEFT` | 2.21 | 223.05 | 6.33002 |
| `IC_PCP` | 4.21 | 253.05 | 12.33002 |

`IC_PCP` selects the low-cost slower VM in this scene; the other SHARED algorithms retain the same
mapping. This is a single controlled observation, not a proof that those algorithms are equivalent.

## LOCAL scene

| Algorithm | Makespan (s) | Total energy (J) | Modeled processing cost |
|---|---:|---:|---:|
| `LOCAL_HEFT` | 2.21 | 223.05 | 6.33002 |
| `DHEFT` | 2.21 | 223.05 | 6.33002 |

The LOCAL scene is retained only for LOCAL-compatible DHEFT/LOCAL_HEFT comparison and is not used to
rank LOCAL against SHARED. DHEFT's pairwise minimum-bandwidth and parent-finish semantics are
verified separately by `DheftPlannerTest`.

## Reproduction command

```bash
mvn -pl :workflowsim-workbench -am test-compile
mvn -pl :workflowsim-workbench -Dit.test=CrossPaperComparisonTest failsafe:integration-test failsafe:verify
```

The comparison uses the production class
`experiments/src/main/java/org/workflowsim/experiments/comparison/CrossPaperComparison.java`.
