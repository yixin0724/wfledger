# Campaigns and the Research Matrix

Before batch runs, first make the comparison units, baselines, inputs, and models explicit. Different entry points carry different responsibilities:

| Entry point | Applicable scenario |
| --- | --- |
| [Workbench](<../getting-started/WORKBENCH.md>) | Compare algorithms within the same decision layer with JSON, and view offline reports |
| Core `ExperimentPlan` / `ExperimentCampaignExecutor` | Declare comparison groups, baselines, and seed designs in Java, and produce generic campaign evidence |
| `NetworkStudyExecutor` | Execute the registered network study matrices; not a generic CLI for arbitrary configurations |

The generic API lives in the core module; research-specific drivers live in the experiments module. Protocols and retention requirements for new studies are covered by the [studies directory conventions](<../../experiments/studies/README.md>); the [algorithm catalog](<../algorithms/CATALOG.md>) is authoritative for algorithm and physical-model combinations.


## Generic Campaign API

Each `Cell` of [ExperimentPlan](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentPlan.java>) declares: the cell ID, the comparison group, the candidate ID, whether it is the baseline, `SimulationConfig`, `PlatformProfile`, `SeedPlan`, and an optional string tag.

The snippet below is an API fragment meant to be embedded in your own Java experiment class, not a standalone program. `config` and `platform` can reuse the ordinary online example from the [Java API guide](<../getting-started/CODE_CONFIG_EXPERIMENTS.md>); `outputDirectory` is a newly created or empty `Path`, and the `org.workflowsim.experiment` types it uses must be imported.

```java
ExperimentPlan plan = ExperimentPlan.builder("scheduler-comparison")
        .addCell(new ExperimentPlan.Cell("fcfs", "same-input", "FCFS", true,
                config.toBuilder().schedulingAlgorithm(SchedulingAlgorithm.FCFS).build(),
                platform, SeedPlan.deterministic(42L), null))
        .addCell(new ExperimentPlan.Cell("mct", "same-input", "READY_BATCH_MCT", false,
                config.toBuilder().schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MCT).build(),
                platform, SeedPlan.deterministic(42L), null))
        .build();
ExperimentCampaignResult result = new ExperimentCampaignExecutor().execute(plan);
ExperimentCampaignArtifactWriter.CampaignArtifacts artifacts =
        ExperimentCampaignArtifactWriter.write(result, outputDirectory);
System.out.println(artifacts.getIndex());
```

The construction phase checks that cell IDs are unique, that candidate IDs are unique within each group, that every group has exactly one baseline, that the randomization design is consistent within a group, that the configuration and platform VM counts agree, and that no comparison group places the online ready-batch cost caliber (raw `length/MIPS` plus charged SHARED read) beside the static planning cost caliber ([TaskExecutionModel](<../../simulator/src/main/java/org/workflowsim/utils/TaskExecutionModel.java>) matrix seconds); [AlgorithmCatalog](<../../simulator/src/main/java/org/workflowsim/experiment/AlgorithmCatalog.java>) owns the classification. [SeedPlan](<../../simulator/src/main/java/org/workflowsim/experiment/SeedPlan.java>) holds the list of actual seeds; the list can be given explicitly or derived from a recorded root via SplitMix64. A deterministic plan may contain only one seed.

These structural checks **do not automatically prove experimental comparability**: the researcher must still pin down the inputs, the platform, the file system, the cost and data movement models, the arrival tables, the failure/overhead settings, and the metric sample scope. Do not mix ordinary-online, static independent-task, static DAG, and online dataflow-binding cells in one group just because two cells can be placed together. The cost-caliber half of that rule is enforced at construction: a group that places an online ready-batch cell (raw `length/MIPS` plus charged SHARED read) beside a planner-decided cell (matrix seconds) fails fast with both calibers named, while each caliber's own comparison semantics stay unchanged; the remaining decision-layer mixes stay the researcher's responsibility, and the workbench CLI keeps its own decision-layer check.

The [executor](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentCampaignExecutor.java>) invokes `SimulationRunner` serially over cells and seeds in declaration order; concurrency within the same JVM is not supported. A parsing, configuration, execution, or retry-budget exception aborts the run; it must not be quietly converted into one ordinary failed-workflow sample. When parallel research is needed, use separate processes with separate outputs, and declare the merging method separately.

## Seed Design and Statistical Interpretation

[RandomizationDesign](<../../simulator/src/main/java/org/workflowsim/experiment/RandomizationDesign.java>) is a declaration of statistical interpretation; it changes neither the simulator's physics nor its random model.

| Design | Available interpretation |
| --- | --- |
| `DETERMINISTIC` | A single deterministic model result and descriptive differences; no sampling confidence intervals |
| `INDEPENDENT_REPLICATIONS` | Per-cell mean, sample standard deviation, and range; a two-sided 95% Student-t interval for the mean when there are at least two samples, and a within-cell Wilson interval for the success rate of qualified workflow runs |
| `COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN` | Descriptive differences matched by identical root seeds plus the paired Wilcoxon test where applicable; not event-keyed CRN, and no CRN treatment-effect intervals |

Identical root seeds do not guarantee that the two algorithms consume the same logical random events. Named random streams isolate the components, but changes to scheduling, batching, or retries can still change the consumption order inside a component. You must inspect the actual seeds and `matchedRootSeedCount`; do not conclude from the design label alone that all runs are paired.

The [campaign summary](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentCampaignSummary.java>) computes the metrics of each run first and then aggregates within cells; it does not pool all Jobs into a single sample. Principal boundaries:

- Only runs with at least one logical Task enter the workflow-run completion rate; a run counts as successful only when every logical Task succeeds.
- `successfulWorkflowLogicalCompletionSeconds` summarizes successful workflows only. When no run succeeds it is unavailable; the simulation end time of failed runs must not stand in for it, and it must not be compared in isolation from the completion rate.
- Runs without an observed true slowdown ratio do not enter that metric's campaign sample; the other metrics keep the zero-value/degenerate conventions of their own APIs and must be interpreted together with the per-run observation counts.
- Continuous-metric intervals and Wilson intervals are within-cell quantities; there are no automatic confidence intervals for algorithm differences, no success-rate difference tests, no hazard ratios, and no proof of universal superiority.
- Ratios are candidate mean / baseline mean; descriptive speedup is baseline mean / candidate mean; the improvement percentage is `100 * (baseline-candidate) / baseline`. When a denominator is zero, the corresponding quantity is unavailable; these are not serial speedup or parallel efficiency.

[PairedWilcoxonSignificance](<../../simulator/src/main/java/org/workflowsim/experiment/PairedWilcoxonSignificance.java>) compares the matched makespan differences candidate−baseline. Zero differences do not enter the rank statistic, but the total pair count, the valid sample count, and the median difference are retained; fewer than two pairs, all-zero differences, only one non-zero difference, or an independent-replications design returns the corresponding `UNAVAILABLE_*` status. With no more than 25 valid non-zero differences the exact distribution over the actual average ranks is computed by subset-sum dynamic programming; beyond that, the library's normal approximation. The normal approximation's continuity and tie-variance limitations remain documented in the source; "non-parametric" must not be read as requiring no statistical assumptions.

[HolmStepDownCorrection](<../../simulator/src/main/java/org/workflowsim/experiment/HolmStepDownCorrection.java>) then applies the Holm (1979) step-down correction within each comparison group: the family is every non-baseline candidate of the same group, and the multiplier base is the declared family size, including comparisons whose paired test returned an `UNAVAILABLE_*` status — those consume correction budget but are reported with a null `holmAdjustedPValue` and are never significant. A comparison is significant after correction only when its adjusted p value is strictly below the 0.05 default alpha. Multiplicity-aware consumers must read `holmAdjustedPValue` / `significantAfterHolmAtDefaultAlpha` instead of the raw paired test verdict. The network study and the Python auditor use the same convention.

The [standard study](<../../experiments/studies/standard/PROTOCOL.md>) builds on these two classes: it pairs over workflow instances rather than seeds, applies Holm within each factor cell and metric, and reports the median per-instance ratio, the matched-pairs rank-biserial correlation, and Cliff's delta as effect sizes.

Before execution, freeze the primary metric, the practical-significance threshold, the number of replications or a pre-defined precision/budget stopping rule, and the multiple-comparison method. The current executor implements no adaptive stopping; do not stop midway because the results look favorable.

### Parameters and Tuning Fairness

Randomized baselines with internal hyperparameters (such as PSO's generation count / particle count / cognitive coefficient, or the seed streams of `RANDOM`) differ from deterministic algorithms — the comparison conclusions are sensitive to "where the hyperparameters came from". Disclosure rules:

- Hyperparameters tied to a baseline/candidate **must be recorded with the protocol** (the planner/scheduler tags of the cell configuration + the algorithm contract fields already contained in the configuration artifact), including the values and their origin (preset values / a grid / external tuning data).
- No <b>post-hoc selective tuning</b>: within the same campaign, the hyperparameters of candidates and baselines must be established under the same preset rules; when one side is grid-searched while the other uses default values, that asymmetry must be stated explicitly in the report.
- Tie-break rules are not tuning: for tie-break/floating-point-sensitive parameters among a baseline's decision hyperparameters (such as the tie-break semantics in PI alignment), the deterministic behavior is itself part of the protocol — changing it means re-registration, and it must not be treated as an implicit degree of tuning freedom.
- The seed streams of randomized baselines follow the [SeedPlan](<../../simulator/src/main/java/org/workflowsim/experiment/SeedPlan.java>) design (multiple seeds for a deterministic algorithm are mere repetition; see the reproducibility contract's declaration in this directory).

## Generic Campaign Evidence

The [campaign writer](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentCampaignArtifactWriter.java>) accepts only a newly created or empty directory. With `EvidenceOutput.FULL` it writes one evidence bundle per cell and replication and then the validated index; with `EvidenceOutput.SUMMARY`, the default, it writes only `campaign-summary.json` with the plan, the run count and the campaign summary:

```text
campaign-output/
  experiment-campaign-index.json
  <cell-id>/
    replication-0000.manifest.json
    replication-0000.metrics.json
    replication-0000.events.jsonl
    ...optional network or lifecycle sidecars for the matching capture mode
```

The index stores the planned seeds, the comparison identities, the relative paths, the run summaries, and the logical workflow profiles. `ExperimentCampaignValidator` cross-checks the references, per-run evidence, seeds, and primary summaries, and independently rebuilds every campaign aggregate: cell samples and intervals, workflow completion, conditional completion times, paired comparisons, and Holm-adjusted results. A summary edited consistently with intact per-run evidence is rejected. This proves neither the representativeness of the sampling nor the research significance of an algorithm combination. Commands are concentrated in [build and verification](<../getting-started/BUILD.md>); metric definitions are in the [semantic contracts](<../algorithms/CONTRACTS.md>).

A generic run may use the nine currently legal physical models plus explicit online binding, subject to each model's configuration/capture prerequisites; support for a model does not mean that every planner can use it.

## Executing the Registered Network Study

[NetworkStudyExecutor](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyExecutor.java>) accepts `smoke|full`, a dataset root, an output directory that must not exist yet, and the optional variants `r10`, `peft-comparison`, and `sensitivity-r13`. Omitting the variant is equivalent to `r10`.

Run one current smoke study from the project root, replacing the absolute-path placeholders:

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  '-Dexec.args=smoke "/absolute/datasets" "/absolute/new-network-study" r10'

mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  '-Dexec.args="/absolute/new-network-study/network-study.json"'
```

The executor freezes the actual declaration of [NetworkStudyPlan](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>) into the output's `protocol.json`; it does not read old Markdown to decide what gets run. The output additionally contains the generated inputs, the per-run evidence, `network-study.json`, and an automatic results table. Conditions must not be modified while keeping the same registered ID (see [protocol identities](#protocol-identities)).

### Protocol Identities

The identifiers below are registered in [NetworkStudyPlan](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>). An identity covers both the protocol ID and the mode; an identical parameter matrix is not an identical execution model.

| CLI variant | Current protocol (`WORK_CONSERVING_TASK_EXECUTION_V3`) | Historical, V2 execution | Historical, undeclared execution | full / smoke plan units |
| --- | --- | --- | --- | ---: |
| `r10` (default) | `network-limited-r10-v4` | `network-limited-r10-v3` | `network-limited-r10-v2` | 504 / 36 |
| `peft-comparison` | `peft-comparison-r12-v3` | `peft-comparison-r12-v2` | `peft-comparison-r12-v1` | 126 / 18 |
| `sensitivity-r13` | `sensitivity-response-r13-v3` | `sensitivity-response-r13-v2` | `sensitivity-response-r13-v1` | 546 / 30 |

Plan units are registered matrix sizes, not completed runs. Smoke has its own inputs, conditions, and seeds. The executor produces only current protocols; historical protocols must declare exactly `WORK_CONSERVING_TASK_EXECUTION_V2` or keep their original shape without the field, and no historical identity may carry the current label.

The executor saves the actual `NetworkStudyPlan.asMap()` as the output's `protocol.json`. The declaration fixes input IDs, order, sources, identities, eligibility exclusions, conditions, planners, and seeds; changing any of them requires a new protocol ID.

[NetworkStudyValidator](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyValidator.java>) accepts current and historical identities and performs a read-only integrity check:

1. The index plan agrees with the retained `protocol.json` and the registered full matrix; pruning, duplication, omission, undeclared conditions, or mode changes are rejected.
2. The raw bytes of `protocol.json` are bound to every run's study ID, protocol logical ID, and driver identity.
3. Each bundle is validated, including configuration, platform resources, prices, storage, topology, and placements.
4. Input identities, algorithm labels, and completion states are checked; an index success mark cannot override an unfinished workflow.
5. Published makespan, logical completion, mean/P95 waiting, and mean VM utilization are checked and the summaries recomputed; utilization must lie in `[0,1]`.

Success output names the protocol, the mode, and `HISTORICAL_PROTOCOL_INTEGRITY` or `DECLARED_PROTOCOL_INTEGRITY`, and is marked `NOT_A_CURRENT_CODE_RERUN`. Integrity of retained evidence is not a rerun under current code; use the [rerun contract](<RERUN_DIFF_CONTRACT.md>) for that. The LOCAL_PEFT of `peft-comparison-r12-v1` and `sensitivity-response-r13-v1` includes the task's own cost in OCT and uses the average cost at the exit, so those results are not standard-PEFT evidence.

### Inputs and Eligibility

- full uniformly excludes Inspiral1000 — its file sizes conflict with a same-named file — from the six classic DAX candidates, and finally uses Epigenomics100/997, CyberShake100/1000, and Inspiral100, plus the deterministically generated layered-32/128.
- smoke uses the ten-task input of HEFT provenance together with layered-16. It is not a primary-source proof of the PEFT algorithm, nor an arbitrarily pruned full.
- Exclusion happens before any algorithm effect is observed and is uniform for all planners; the input order, hashes, and exclusion reasons must match the registered declaration. An eligibility failure or an input change must not be bypassed by swapping files, skipping runs, or renaming.

### Fixed Conditions

These registered studies use **LOCAL/STATIC, NONE clustering, SPACE_SHARED, no failures/overheads, no task cost matrix, 1 MB/s endpoints**, with one fixed Host per VM. The networks are V1 endpoint contention and Fat-tree contention, both starting transfers when a Job becomes ready; they are not a coverage matrix of coherent V2/V3.

| Variant | full conditions | Planners and actual seeds |
| --- | --- | --- |
| `r10` | VM 4/16 × endpoint, fat-tree-constrained, fat-tree-wide | `LOCAL_HEFT`, `LOCAL_CPOP` once each at seed 11; `RANDOM`, `PSO` at 11/29/47/71/101 |
| `peft-comparison` | same as the row above | `LOCAL_HEFT`, `LOCAL_CPOP`, `LOCAL_PEFT`, once each at seed 11 |
| `sensitivity-r13` | homogeneous main block VM 4/8/16/32 × the five network tiers; plus three heterogeneity modes at VM 8/16 under constrained | `LOCAL_HEFT`, `LOCAL_CPOP`, `LOCAL_PEFT`, once each at seed 11 |

The Fat-tree links of constrained/mid/wide/fast are 0.125/0.5/1.25/5 MB/s; the first two variants use constrained/wide only. The default homogeneous VMs are 1000 MIPS; the heterogeneous cycle modes are `[1000,500]`, `[2000,1000,500]`, `[2000,500]`. k=4 when VMs do not exceed 16, and k=8 at VM32. Changing the VM count may also change the topology, and heterogeneity changes the total capacity as well; do not attribute all effects to a single factor.

The first two smoke variants use VM4 only; the randomized planners use seeds 11/29. The sensitivity smoke uses the VM4 endpoint/constrained homogeneous conditions plus the three heterogeneity modes under constrained. The authoritative plan-unit counts are those of the registered declaration; they do not mean these runs have already been executed.

### Network-Study Statistics and Failure Handling

[NetworkStudySummary](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudySummary.java>) first averages the seeds within each DAG, then stratifies by source, VM, network, and applicable heterogeneity, pairing against LOCAL_HEFT. It uses a **two-sided sign test**, not the generic campaign's Wilcoxon; Holm corrects within the candidate family of the same stratum. The win/tie/loss threshold is the registered `1e-9 * max(1, baseline, candidate)`, not the rerun numeric tolerance.

The classic and synthetic populations are kept separate; seeds are not treated as additional DAGs. The classic full has only five DAG pairs; even if every pair points the same way, the minimum raw p of the two-sided sign test is 0.0625, so non-significance does not prove equivalence. Report the effect sizes, the per-DAG distributions, and the failure states; do not extrapolate exploratory findings from a fixed corpus to real clouds.

The study executor keeps an individual failure on record and continues with the other units, but a failed study produces no valid inference and fails the final integrity validation. An interrupted index is marked `RUNNING_OR_INTERRUPTED`; existing aggregates must not pass themselves off as the full matrix. Read-only validation checks the registered matrix, the original-byte hash of the protocol, the complete run configurations/platforms, the input identities, the completion states, the index metrics, and the recomputed summaries; independent statistical and physical checks are covered by the [audit tooling](<../../scripts/STUDY_AUDIT.md>).

Retain the original bytes of the protocol, the index, all referenced evidence, and the generated inputs — not only the results tables. Whether the current code actually reruns consistently is judged separately by the [rerun difference contract](<RERUN_DIFF_CONTRACT.md>).
