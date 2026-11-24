# V&V Case and Golden-Evidence Ledger

This ledger binds scientific claims to their intended use, evidence, validation domain, and
explicit residual risk. It separates code verification, solution verification, and operational
validation. Reproducibility or internal consistency must never be relabeled as real-platform
validity. The ledger **does not produce a single credibility score**: capability and results are
reported separately, and a failed or unexamined critical case cannot be averaged away.

Method references: [NASA-STD-7009B](https://standards.nasa.gov/standard/NASA/NASA-STD-7009),
[Sargent's V&V framework](https://doi.org/10.1057/jos.2012.20), and
[ASME V&V 20](https://www.asme.org/codes-standards/find-codes-standards/standard-for-verification-and-validation-in-computational-fluid-dynamics-and-heat-transfer).
These are methodological references, not a claim of standards certification.

## 1. Required case record

Every research claim or benchmark case must be reviewable from the following fields:

| Field | Required meaning |
|---|---|
| `caseId` | Stable ASCII identifier; never recycle it for a different protocol |
| `intendedUse` | Decision or scientific question the evidence supports, plus its criticality |
| `codeIdentity` | Source-tree identity and, when applicable, a local commit; source hashes are not release signatures |
| `protocolIdentity` | Original protocol bytes and logical ID; protocol revisions receive a new identity |
| `inputIdentity` | Original input paths, order, formats, SHA-256, normalization, and population label |
| `referentIdentity` | Paper, analytic model, reference implementation, or real-system observation, with version and pedigree |
| `verificationMethod` | Hand derivation, contract, invariant, reference differential, rerun, or independent recompute |
| `validationPoint` | Exact input, platform, model, configuration, and output variables examined |
| `metricAndThreshold` | Comparison metric and acceptance criterion registered before execution |
| `expectedValueSource` | One of the legal evidence classes below; never an unlabeled copy of production output |
| `result` | Numeric outcome, comparison, uncertainty/sampling boundary, and command/evidence location |
| `coverage` | Conditions actually checked and the domain not checked |
| `warnings` | Declaration-only fields, unquantified uncertainty, defects, and forbidden extrapolations |
| `review` | Reviewer, independence, date, disposition, and any accepted residual risk |

A missing or `NOT_ESTABLISHED` case is not a passing case. `NOT_APPLICABLE` requires a written
reason. A golden regression detects drift but does not prove initial correctness. The
[golden and reference-evidence registry](<GOLDEN_EVIDENCE_REGISTRY.md>) records the current anchors
and their legal evidence classes.

## 2. Legal evidence classes

| Class | Role and permitted claim |
|---|---|
| `PAPER` | Literal primary-literature input or table with an independent hand derivation; supports its stated semantic domain only |
| `CONTRACT` | Expected behavior derived from the current project contract, including units, state transitions, and fail-fast rules |
| `INVARIANT` | Conservation, partition, non-overlap, ordering, capacity, or identity derived independently of the implementation |
| `REFERENCE_IMPLEMENTATION` | A versioned upstream or independent implementation used for a declared model intersection; not real-world validation |
| `GOLDEN_REGRESSION` | A labeled frozen result used only to detect drift; its original source and protocol must still be recorded |

Real-system observations additionally require the platform, scheduler, clustering, network, WMS,
data collection procedure, and measurement uncertainty. A workflow instance's real provenance
alone does not supply all of these conditions.

## 3. Assessment dimensions and states

`CAPABILITY` records data pedigree, code verification, validation, development technical review,
and development process management. `RESULTS` records use assessment, input pedigree,
uncertainty characterization, results robustness, use/analysis technical review, and use process
management. Each row is reported independently; there is no average or pass/fail total.

| State | Meaning |
|---|---|
| `VERIFIED` | The named check passed at the stated validation point; it is not a broader validity claim |
| `DIVERGED` | A comparison failed and requires disposition before the associated claim can be used |
| `NOT_ESTABLISHED` | Required evidence or a referent is missing; no positive claim is allowed |
| `NOT_APPLICABLE` | The case is outside the declared use, with the reason and approval recorded |
| `ACCEPTED_RISK` | A known limitation is retained explicitly, with its scope, consequence, and approval |

## 4. Current baseline cases (2026-11-04)

| caseId | Evidence and current state | Domain and residual boundary |
|---|---|---|
| `REPRO-EXACT-001` | `VERIFIED`: full Maven gate and exact rerun contracts; see [reproducibility](<REPRODUCIBILITY.md>) and [rerun diff](<RERUN_DIFF_CONTRACT.md>) | Same declared code, inputs, and configuration; not a real-platform or cross-version equivalence claim |
| `MUT-CORE-001` | `VERIFIED`: 31 registered canaries across simulator and experiments, including M22–M31; see [mutation gate](<../advanced/MUTATION_GATE.md>) | Registered semantic canaries, not an exhaustive mutation score; experiments coverage grows through its own registry and `-am` runner |
| `PERF-PROFILE-001` | `VERIFIED`: isolated-JVM `ONLINE_V3` profile smoke and independent comparator pass two cases with matching semantic fingerprints, guarded by M31 | No wall-clock performance threshold or universal speedup claim; larger trends require separately retained environment identities |
| `STATS-WILCOXON-001` | `VERIFIED`: exact tied-rank p values use the actual average ranks, with external exact/normal references and the M28 rollback canary | Exact path limited to 25 effective nonzero differences; the normal approximation retains its declared continuity/tie limitations |
| `NUM-TICK-001` | `VERIFIED`: hand-derived tick cases at 0.1, 0.05, and 0.025 seconds, guarded by M29 | Tick is a declared model parameter, not a numerical-convergence or network-latency calibration |
| `BENCH-FROZEN-001` | `VERIFIED`: standard 160, power 160, carbon 170, DVFS 90 bundles independently audited after fresh restoration; zero issues | 580 historical evidence bundles; validates their registered evidence contracts, not real-platform calibration or current regeneration of every protocol |
| `CAMPAIGN-SUMMARY-001` | `VERIFIED`: generic campaign aggregates are independently rebuilt from validated per-run evidence, with deterministic and independent-replication tamper cases and the M22 canary | Covers the serialized aggregate summary, not sampling representativeness or the scientific validity of an algorithm combination |
| `UPSTREAM-KERNEL-001` | `VERIFIED` for the declared B02/B03 intersection: CloudSim serial/send, Space-Shared, and legacy SHARED hand-derived references with M24–M26 canaries; see [reference implementation](<REFERENCE_IMPLEMENTATION.md>) | Does not cover old labels, complete examples, failure semantics, or real-platform validation; those remain separate cases |
| `WF-APPS-STD-001` | `VERIFIED`: all 20 classic DAX and the one tracked Montage WfInstances pass independent DAG, runtime, file-byte, schema, and missing-field checks; see [application benchmarks](<APPLICATION_BENCHMARKS.md>) | Standard-checkout structural verification only; no optional-corpus or production-trace replay claim |
| `WF-APPS-EXT-001` | `VERIFIED`: all 17 Montage WfInstances pass the same checks under the explicit `extended-corpus` profile after the full local snapshot and index are prepared | Optional-corpus structural verification only; not a clean-checkout requirement or real-platform validation |
| `REAL-PLATFORM-001` | `NOT_ESTABLISHED`: no complete production-cluster referent with platform, scheduler, clustering, network, WMS, and measurement uncertainty | Real-platform validation is not claimed; a future limited case requires independent approval and a complete referent |
| `REVIEW-INDEPENDENT-001` | `VERIFIED`: independent structured technical review approved the internal-validity scope after VV-NEW-01 was closed; see the [review record](<../../experiments/studies/verification/VV_REVIEW.md>) | No human independent signoff or real-platform validation is claimed; semantic changes reopen the affected cases |
| `PAPER-CAWOSCHED-001` | `VERIFIED_TIMED_CORE`: generic TimedPlan API, all 16 variants, communication activities, exact single-processor DP, absolute-start dispatch, M32–M35; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No full paper corpus, multi-processor ILP, Workbench JSON, or carbon-optimality claim |
| `PAPER-CWS-RANKMIN-001` | `VERIFIED_STATIC_SUBSET`: CWS bottom-rank, input-byte tie-break, persistent RR, exact Figure 1 DAG and 5.21→4.21 direction, M36; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | Static-DAG/idle-VM subset only; no dynamic CWS API, future task creation, or real-cluster claim |
| `PAPER-NACO-001` | `VERIFIED_HYBRID_CORE`: DNC evaluation, elite HEFT, GPX/mutation/replacement, named random stream, LOCAL integration, M37/M38; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No full paper corpus, accuracy percentage, optimal makespan, or real-platform validation claim |
| `PAPER-RESHI-001` | `VERIFIED_RANKED_SUBSET`: Reshi-C/M, explicit rank/history, session injection, prediction-error clamp and M39; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No regression-tree training, AWS profiling, or real-cluster validation claim |
| `PAPER-GREENHEFT-001` | `VERIFIED_ENERGY_CORE`: HEFT priority, energy-first VM choice, declared-power integration and M40; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No empirical neural model, MOHEFT Pareto front, or full paper-corpus claim |
| `PAPER-MOHEFT-001` | `VERIFIED_PARETO_CORE`: HEFT-ordered beam search, dominance, crowding extremes, balanced selection and M41/M42; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No exact full Pareto optimality, empirical neural model, or full paper-corpus claim |
| `PAPER-ICPCP-001` | `VERIFIED_DEADLINE_COST_CORE`: PCP, EST/LFT, path cost, END deadline slack, fork/join, infeasibility and M43/M44; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No IC-PCPD2, dynamic provisioning, or full paper-corpus claim |
| `PAPER-NETWORK-HEFT-001` | `REMOVED` (FGCS-R3, 2026-11-24): planner removed because the SHARED runtime it was bound to has no point-to-point flows | No evidence is claimed |
| `PAPER-EBPSM-001` | `VERIFIED_BUDGET_CACHE_CORE`: per-workflow SFTD (Algorithm 1), Algorithm 3 budget update (M71), input/container cache preference, budget gate, staggered multi-workflow integration and M46/M47; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No Docker deployment, elastic provisioning, MCPA substitution, or full WaaS corpus claim |
| `PAPER-DVFS-001` | `VERIFIED_SLACK_CORE`: HEFT allocation, energy-decreasing slack optimization, logical-Task tier registry, full runner tier verification and M48; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No global DVFS optimality or full paper-corpus claim |
| `PAPER-CRCH-001` | `VERIFIED_RECOVERY_CORE`: synchronized checkpoints, remaining-work retry, alternative VM, real failure recovery and M49/M50; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No replication-count learning, PCA clustering, or full CRCH replication claim |
| `PAPER-DHEFT-001` | `VERIFIED_DHEFT_CORE`: ready-task EFT, pairwise minimum bandwidth, parent finish, LOCAL integration and M51/M52; see [paper reproductions](<PAPER_REPRODUCTIONS.md>) | No full dynamic-arrival or production-cluster claim |
| `PAPER-COMPARE-001` | `VERIFIED_COMPARISON_SCENE`: SHARED/LOCAL real-runner scenes, distinct mappings, direct IC-PCP planner cost, independent energy residual and M53; see [cross-paper comparison](<CROSS_PAPER_COMPARISON.md>) | Single two-task DAG only; LOCAL scene is non-discriminating; no universal ranking or mixed-model population |
| `AUDIT-ALGO-METRIC-001` | `VERIFIED_ALGORITHM_METRIC_CORE`: all reproduced families reviewed and corrected; independent makespan/energy/cost/tail recomputation, M54–M65; see [algorithm and metric audit](<ALGORITHM_METRIC_AUDIT.md>) | No exact paper-table, full-corpus, global-optimality, or real-platform calibration claim |
| `AUDIT-EXPERIMENT-001` | `VERIFIED_EXPERIMENT_CORE`: 82 real matrix runs plus special/metamorphic cases; successful-parent and CRCH remaining-work corrections, M66/M67; see [experiment audit](<ALGORITHM_EXPERIMENT_AUDIT.md>) | No universal correctness or full-corpus claim; new models need new experiment axes |

## 5. Change and review discipline

1. Register the intended use, metric, threshold, and referent before running a new study.
2. Write the failing test first whenever a new verification rule or defect fix is introduced.
3. Preserve original inputs, protocols, and evidence. Audit only fresh restorations and write reports to new paths.
4. Record differences instead of hiding them through renamed labels, edited goldens, or new whitelist exemptions.
5. Reopen the affected cases after semantic, input, referent, platform, or metric changes. A green build does not close a missing validation case.
6. Keep accepted boundaries visible in reports. In particular, `IDENTICAL_CORE`, archive integrity, and dual-language recomputation are reproducibility/verification evidence, not empirical validation.
