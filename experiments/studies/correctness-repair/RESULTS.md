# Research-impact re-check after the correctness repair

Status: all three new protocol matrices have actually been executed to completion, and both the production validator and the independent mathematical re-check pass; the isolation control for the designated large-change case is complete. This document is an **impact re-check of an implementation repair**, not a preregistered experiment written after the fact, and it does not reinterpret the old model as the current standard algorithm.

## Execution identity and protection

- Starting point: R13 merge commit `97c66a03101d5bd6e75b67127c5a894b6e656342`.
- Corrected Java implementation: `8681f9c`, execution declaration `WORK_CONSERVING_TASK_EXECUTION_V2`; LOCAL_PEFT uses `PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`.
- Every run uses the same input hashes, condition keys, and original parameter matrix; only the new protocol identities and the corrected implementation differ.
- The protocols, indices, and compressed evidence bundles of the old R10/R12/R13 studies stay exactly as they were; the 1176 artifacts of the three old studies pass the current historical-integrity check. This does not mean the old computations or the old PEFT definition were correct.
- New output is retained independently under `output/workbench-correctness-repair-97c66a0/studies/` and does not overwrite the old directories. The complete run protocol, inputs, per-run v4 triplets, and machine result tables are retained alongside it; this page in the source tree is only the interpretation and retention note.

## Matrix completion

| Parameter matrix | Historical protocol | New protocol | Runs | Production validation status |
|---|---|---|---:|---|
| Network-limited | network-limited-r10-v2 | network-limited-r10-v3 | 504 | COMPLETE / PASSED |
| PEFT comparison | peft-comparison-r12-v1 | peft-comparison-r12-v2 | 126 | COMPLETE / PASSED |
| Sensitivity response surface | sensitivity-response-r13-v1 | sensitivity-response-r13-v2 | 546 | COMPLETE / PASSED |

The three new studies total 1176 runs. Inputs, resources, model, and seed stay identical within each condition; different sources, VM counts, network, and heterogeneity conditions remain stratified, and repeated conditions must not be treated as independent DAGs.

## Per-condition comparison against historical values

Rows are aligned on `(workflowId, VM count, network, heterogeneity, planner label, seed)` and the input SHA equality is checked. The table below only describes the recorded changes; it is **not a new correctness proof or significance test**; in particular, the old `LOCAL_PEFT` label corresponds to a recursion that has been shown to be non-standard.

| Matrix | Aligned runs | Simulation end time changed | Any of the five core fields changed |
|---|---:|---:|---:|
| R10 | 504 | 161 | 217 |
| R12 | 126 | 97 | 101 |
| R13 | 546 | 414 | 440 |

The five fields are simulation end, logical completion, mean/P95 waiting, and mean VM interval utilization. A value-by-value change is not the same as a practically meaningful difference, nor can a single change be attributed directly to one particular fix; this round corrected CPU progress, the LOCAL data-reachability estimate, and the PEFT recursion at the same time.

Some LOCAL planning scenarios show large changes. For example Cybershake100, 16VM, endpoint, HEFT goes from 41629.930933 s to 283759.323274 s; under the narrower Fat-tree link it goes from 43362.237990 s to 312137.928208 s. A passing new gate is no reason to claim superiority, nor may parameters be tuned to preserve the old winner.

### Isolation control for Cybershake100 / 16VM / HEFT

Only the planner base class is substituted on the audit classpath in a separate process; committed source, bandwidth, and the original study are not modified:

| Control combination | endpoint end (s) |
|---|---:|
| Original baseline, full core | 41629.930933 |
| New execution core + old planner base class | 41629.930933 |
| New execution core + old planning with only the root external-input estimate added | 283137.050000 |
| New execution core + full new planning | 283759.323274 |

The full new planning gives 41616.562860 s under the **contention-free run model**, matching its own planned endpoint; that contention-free prediction must not be taken directly as the endpoint contention execution time. The evidence shows that the large change in this case comes mainly from the placement that follows the changed root-input readiness estimate, not from the CPU execution fix magnifying single-task work several-fold.

This input has 8 ExtractSGT roots with roughly 39-41 GB of external input each. Old planning spread them over 8 VMs; new planning puts 7 of them on VM0, 7 groups totaling 278.145799951 GB, so the service lower bound on the 1 MB/s ingress is 278145.799951 s. Task23's input arrival time changes from 38738.009440 to 271164.806080, the latter exactly equal to `0.21 + 7 * 38737.79944`.

The fixed per-VM order also produces directly observable head-of-line blocking: on VM1, Task3's data is already ready at 40305.069834, yet the event records 16 idle VMs and 0 dispatches; it must wait for the planned prefix blocked by the slow root on VM0 and only executes at 278543.895748. Of that 238238.825914 s wait, VM1 was busy for only 115.56 s. Intra-VM queue waiting of 0 therefore does not mean there is no scheduling wait. Total compute work is 3215.75 s in both the old and the new version, not magnified several-fold. The complete per-task and event evidence is in `output/workbench-correctness-repair-97c66a0/impact-analysis/REPORT.md` (an optional retained output of the original workspace).

This is a control for the designated case, not a unified causal attribution of every change; the model correction also does not guarantee that every heuristic gets faster on every workload. The LOCAL policy in R10 carries a per-VM order, whereas RANDOM/PSO only map and may select any ready job at run time, so it compares complete policy pipelines and cannot be explained as the merit of the mapping optimization alone. The three LOCAL list planners in R12/R13 use the same in-order execution discipline.

## Algorithmic identity of PEFT

Chapter 3 of the author's publicly available doctoral thesis explicitly includes the article with DOI `10.1109/TPDS.2013.57`. Printed p71 Eq7 uses the successor cost with exit OCT=0, and p73 Algorithm1 uses a ready-list with insertion-based EFT; the [primary-source note](<../../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>) records the public source and the transcription-verification method.

The genuine paper fixture has been verified at PEFT 122 and HEFT 133 (with the explicit 110.1 bootstrap counted, 232.1/243.1). The original HEFT-origin fixture gives 85 under the correct PEFT recursion, not the old 76. The old R12/R13 interpretations of "homogeneous degeneration", "heterogeneous activation", or PEFT's relative merit cannot be inherited directly as conclusions about standard PEFT.

## Statistical and model-interpretation boundaries

- The classic source has only 5 DAGs and the synthetic source only 2; the populations are not pooled. With 5 pairs the two-sided exact sign test has a minimum raw p of 0.0625 and cannot reach the 0.05 threshold; non-significance does not mean equivalence.
- Randomized algorithms aggregate seeds within each DAG first and are then paired by DAG; identical root seed does not automatically become an event-keyed CRN.
- The heterogeneity axis also changes total MIPS; VM32 additionally changes the Fat-tree k, so cross-condition comparison supports no single-factor causal attribution.
- The per-DAG distribution must be examined; a near-zero median does not imply per-DAG stability, and makespan alone does not prove the OCT mechanism.
- The model has no calibration against real hardware, network, or prices. Static LOCAL planning still uses contention-free estimates and is not a full network event replay.

## Independent re-check and retention locations

The [independent tools](<../../../scripts/STUDY_AUDIT.md>) do not import Java production functions. Final actual execution results:

| Independent check | Scale | Result |
|---|---:|---|
| Raw inputs, Job/Task/events, CPU lower bound, and metrics | 1176 bundles, 25,772,522 rule checks | 0 issues |
| Canonical matrix, seed aggregation, per-DAG effects, exact sign test/Holm | 3 studies, 89,848 checks | 0 issues |
| Core fields of conditions shared by the same model | R10/R12 intersection 84, R10/R13 intersection 84, R12/R13 intersection 126 | All consistent |
| Checker self-tests on small positive/negative cases | 11 items | All passed |

Check counts do not equal independent sample counts. The CPU physical tolerance is 1/MIPS quantization plus absolute/ULP error and is not relaxed by an absolute clock ratio; aggregate numbers use separate explicit relative/absolute tolerances. Each bundle lists the metrics actually recomputed and the out-of-scope items, and per-flow network allocation is not passed off as replayed because a complete ledger is missing.

The following are the optional retention locations of the original workspace, all relative to the project root; locally generated artifacts are not provided with a normal Git checkout, and the recorded paths are not guaranteed to exist on the current machine:

- New R10 index: `output/workbench-correctness-repair-97c66a0/studies/network-limited-r10-v3/network-study.json`.
- R12 index: `output/workbench-correctness-repair-97c66a0/studies/peft-comparison-r12-v2/network-study.json`.
- R13 index: `output/workbench-correctness-repair-97c66a0/studies/sensitivity-response-r13-v2/network-study.json`. Each of the three indices keeps the raw protocol JSON, inputs, result tables, and all run triplets in its own directory.
- Independent evidence report: `output/workbench-correctness-repair-97c66a0/independent-evidence-new-v2.json`; independent statistics and per-DAG distributions: `output/workbench-correctness-repair-97c66a0/independent-statistics-new-v2.json`; old-versus-new condition comparison: `output/workbench-correctness-repair-97c66a0/research-impact-deltas.json`.
- Large-change isolation control: `output/workbench-correctness-repair-97c66a0/impact-analysis/REPORT.md`, together with the probes, commands, per-task CSV, and control JSON in the same directory.

A passing production validator, independent arithmetic agreement, reproduction of the published algorithm, and calibration against real hardware are distinct evidence levels. This round completed the first three within their declared scope and does not treat them as general conclusions about real networks or all workloads.
