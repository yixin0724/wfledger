# Sensitivity Response-Surface Study (R13): Protocol and Retention Notes

> **Historical archive: non-standard PEFT, not a current algorithm guide.** This page retains in
> place the preregistration, adjudication rules, and study conditions of
> `sensitivity-response-r13-v1`. The legacy `LOCAL_PEFT` wrongly counted its own `w(t,p)` into
> OCT and took the averaged computational cost at the exit; the ready-list fix did not correct
> that formula. The "homogeneous degeneration / heterogeneous activation" reading below, the
> validation claims, and the numbers describe only the implementation of that time and cannot
> serve as evidence for or against standard PEFT or for its mechanism; the original
> preregistered text is not rewritten.
>
> The standard definition is given on the author's
> [public repository record](<https://repositorio-aberto.up.pt/handle/10216/92290>) and in the
> [full doctoral dissertation](<https://repositorio-aberto.up.pt/bitstream/10216/92290/2/129782.pdf>),
> Chapter 3, printed p.71 (Eq.7: successor `w(child,p')`, exit `OCT=0`) and p.73 (Algorithm 1).
> The current executor produces `sensitivity-response-r13-v2`; the historical run commands below
> do not restore the legacy implementation. The current protocol and the retained corrected-model
> study are in [protocol revisions](<../../../docs/experiments/NETWORK_STUDY_PROTOCOL.md>)
> and [study-impact re-verification](<../correctness-repair/RESULTS.md>), respectively.
>
> `output/...` is an optional evidence location of the original workspace and is not provided
> with a normal checkout; archiving this page does not rewrite the original protocol JSON,
> numeric artifacts, or tar archive.

Protocol ID: `sensitivity-response-r13-v1`. The Java driver reuses
`org.workflowsim.experiments.network` (the sensitivity-r13 variants of
NetworkStudyPlan/Executor/Summary/Validator). This study is a **descriptive sensitivity response
surface**: it asks how the planners' relative gains change along three axes (VM count, link
bandwidth, and VM heterogeneity), and it performs no parameter fitting or extrapolation.

## Relationship to the frozen evidence

This study modifies no artifact of the R10 protocol `network-limited-r10-v2` (504 runs,
`output/network-study-r10-final/`) or of the S5 protocol `peft-comparison-r12-v1` (126 runs,
`output/peft-comparison-r12/`); every extension is a conditional code path, and re-validation of
the frozen indices is byte-for-byte unchanged (re-checked for this study; both reported
`NETWORK_STUDY_VALIDATION PASSED`).

## Matrix

- Qualified inputs: the 7 inputs of R10/S5 (5 classic DAXs: Epigenomics 100/997, CyberShake
  100/1000, Inspiral 100; 2 deterministic synthetic DAGs: layered-32/128). Inspiral 1000 is
  excluded entirely because of CONFLICTING_FILE_SIZE.
- Algorithms: LOCAL_HEFT, LOCAL_CPOP, LOCAL_PEFT, all deterministic, one run per condition
  (seed 11). No failures, LOCAL file system, STATIC dispatch, SPACE_SHARED, one VM per Host,
  fixed placement (id ↔ id).
- **Main block (homogeneous response surface)**: VM ∈ {4, 8, 16, 32} × network ∈ {endpoint 1.0,
  fat-tree-constrained 0.125, fat-tree-mid 0.5, fat-tree-wide 1.25, fat-tree-fast 5.0 MB/s};
  all VMs homogeneous at 1000 MIPS. 20 conditions in total.
- **Heterogeneity block (OCT mechanism check)**: VM ∈ {8, 16} × fat-tree-constrained ×
  heterogeneity ∈ {HET_MILD, HET_STRONG, HET_EXTREME}. The MIPS pattern is a deterministic
  formula by VM index i: MILD = 1000(i even)/500(i odd); STRONG = `[2000,1000,500][i%3]`;
  EXTREME = 2000(i even)/500(i odd). 6 conditions in total.
- Production run volume: 7 inputs × 26 conditions × 3 algorithms × 1 seed = **546 runs**.
- CI smoke: paper ten-task fixture + layered-16, main block 4VM × {endpoint, constrained},
  heterogeneity block 4VM × constrained × 3 tiers, 5 conditions × 3 algorithms = 30 runs.

### Dependence of fat-tree k on VM count (must be presented with the results)

A fat-tree with k=4 carries only 16 hosts; a condition with VM=32 must use k=8 (128 hosts). The
last tier of the VM axis therefore changes both the topology level and the host-pool size at
once, so **comparisons between VM=32 and the other tiers contain a topology confound**: they are
descriptive only, and the difference must not be attributed to "more VMs" alone. The remaining
network and heterogeneity axes are not affected.

## Preregistered mechanism check (extension of the S5 finding)

On a homogeneous platform, S5 found no PEFT advantage over HEFT and many exact ties, because the
OCT lookahead term takes the same value for all homogeneous VMs and the lookahead degenerates to
a constant. R13 therefore **preregisters** the prediction:

> LOCAL_PEFT activation (median improvement % vs LOCAL_HEFT clearly deviating from 0) should
> appear **only** under heterogeneous conditions (HET_MILD/HET_STRONG/HET_EXTREME); across all
> homogeneous conditions (the 20 main-block conditions), the PEFT-vs-HEFT median improvement %
> should be close to 0 and tie-dominant. The homogeneous control for a heterogeneous condition
> is taken from the constrained condition of the main block with the same VM count and network.

Adjudication rules (same structure as in S5): DAG-paired median improvement % with exact sign-test
p-values and Holm adjustment (one family per source/VM/network/heterogeneity, family size 2). The
tie tolerance is relative 1e-9. A prediction counts as "activation" only if both conditions hold:
the median improvement % clearly deviates from 0, and at least one heterogeneous condition's
Holm-adjusted p-value provides directionally consistent evidence (still interpreted as
exploratory; see below).

## Metrics and statistics

The primary metric is simulation end time, supported by logical task completion time, mean/P95
waiting, and VM utilization. Every run must complete all logical tasks successfully; failure
records are retained, and the validator rejects failed, missing, duplicated, or inconsistent
artifacts. The validator checks the complete matrix by condition (including heterogeneity),
verifies that each artifact's VM MIPS / topology k / link bandwidth matches the declaration, and
independently recomputes the summary statistics.

Deterministic planners run once per condition, with the DAG as the pairing unit. The classic (5)
and synthetic (2) sources are **never pooled** and are summarized separately.

## Retention notes (must be presented together with the results)

1. **Descriptive, not causal**: the response surface only makes within-condition comparisons and
   cross-condition descriptions; it fits no parametric model and extrapolates to no unsimulated
   configuration.
2. **VM=32 topology confound**: see above.
3. **Exploratory selected corpus**: the 7 inputs are fixed by selection, not randomly sampled;
   the p-values do not support extrapolation to production platforms or to all workflows;
   non-significance does not mean algorithm equivalence.
4. **Small-sample power**: the classic group has 5 paired DAGs, so sign-test power is limited;
   the synthetic group has only 2 DAGs, so any p-value is blunt.
5. **No external calibration**: all bandwidth/MIPS values are abstract model declarations, not
   calibrated against a real cloud network.
6. The study used no trainer.

## Artifacts and reproduction

The complete evidence is kept in the project at `output/sensitivity-r13/` (excluded by
`.gitignore`, not committed; the handover archive is
`output/sensitivity-r13-evidence.tar.gz`). Each run has a v4 manifest, metrics, and events; the
study root contains the protocol, a 546-row run index, the auto-generated result table, and the
synthetic inputs.

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full /absolute/datasets /absolute/new-output sensitivity-r13"
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="/absolute/new-output/network-study.json"
```

The output directory must not exist. The check passes when the validator prints
`NETWORK_STUDY_VALIDATION PASSED runs=546`.
