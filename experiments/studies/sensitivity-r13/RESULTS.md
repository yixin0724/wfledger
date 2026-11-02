# Sensitivity Response-Surface Study (R13): Results

> **Historical archive: the original assessment is retained, and it does not certify standard
> PEFT.** The 546 records below belong to `sensitivity-response-r13-v1`. The legacy `LOCAL_PEFT`
> wrongly counted its own `w(t,p)` into OCT and took the averaged computational cost at the exit;
> the ready-list fix described in the body did not correct that formula. Neither successful
> execution, nor legacy goldens, nor passing integrity checks can make the original
> interpretations ("degeneration / activation / optimistic bias") evidence about standard PEFT.
> The original preregistered adjudication, the numeric tables, and the assessment body are
> retained unchanged.
>
> The author's [public repository record](<https://repositorio-aberto.up.pt/handle/10216/92290>)
> and the [full dissertation](<https://repositorio-aberto.up.pt/bitstream/10216/92290/2/129782.pdf>),
> Chapter 3, printed p.71 (Eq.7 / exit condition) and p.73 (Algorithm 1), give the successor
> `w(child,p')` and the exit `OCT=0`. The current executor produces
> `sensitivity-response-r13-v2`, not the legacy results on this page; the current contract and the
> retained corrected study are in
> [protocol revisions](<../../../docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>) and
> [study-impact re-verification](<../correctness-repair/RESULTS.md>).
>
> The `output/...` paths in this document are optional retention locations of the original
> workspace and are not provided with a normal checkout. The legacy reproduction commands below
> do not restore the historical implementation under the current code; overwriting the original
> results or the tar archive is forbidden.

- Protocol: `sensitivity-response-r13-v1` ([PROTOCOL.md](<PROTOCOL.md>); the preregistered
  content was written before the experiment)
- Evidence: `output/sensitivity-r13/network-study.json` + `runs/` (packed as
  `output/sensitivity-r13-evidence.tar.gz`, gitignored)
- Scale: 7 workflows × 26 conditions × 3 planners (seed 11) = **546 runs, 0 failed**
- Validation: built-in executor checks + independent Validator
  `NETWORK_STUDY_VALIDATION PASSED runs=546`; re-validation of the frozen evidence still reports
  PASSED at `runs=504` / `runs=126`
- Baseline: LOCAL_HEFT; candidates: LOCAL_CPOP, LOCAL_PEFT; improvement % =
  (HEFT−candidate)/HEFT×100, positive = faster
- Inference scope: each layer is an exploratory selected corpus (5 classic DAGs or 2 synthetic
  DAGs), populations are never pooled; DAG-paired sign test + Holm correction (family=2 per
  layer)

## 1. First-run failures and the planner fix (process record)

The full first run had 18/546 LOCAL_PEFT cells fail, all of them in
{epigenomics-100, epigenomics-997, inspiral-100} × vm{8,16} × {HET_MILD, HET_STRONG, HET_EXTREME}.
Root cause: under heterogeneous MIPS, the PEFT paper's descending rank_o order does not guarantee
a topological order (reproduction: in epigenomics-100 vm8 HET_MILD, parent 56 is ordered after
task 80), and the base class's `readyTime` fails fast on the boundary declaration designed for
R12. The cross-VM OCT gap of deep-chain classic DAGs amplified that inversion; cybershake and the
synthetic DAGs did not trigger it.

Fix (commit 37d3258): LOCAL_PEFT assignment was changed to the HEFT-family standard **ready-list
discipline**: each round takes, among the tasks whose predecessors are all assigned, the one with
the highest rank_o (ties broken by the smaller task ID). When the rank_o order is itself
topologically legal (all homogeneous conditions and the paper's worked example), the ready-list
selection agrees with the literal rank_o order item by item and the schedule is unchanged position
by position: the paper's exact-reproduction unit tests (OCT table / schedule / makespan=76) and
all existing PEFT unit tests stay green (10/10), and a heterogeneous-inversion unit test was
added. After the fix all 546/546 runs succeed.

This is an extension of research capability (heterogeneous OCT semantics), not a revision of
existing conclusions: the frozen R10/S5 evidence (homogeneous conditions) is unaffected, and
re-validation reports PASSED.

## 2. Adjudication of the preregistered mechanism check

**Prediction (written into PROTOCOL.md before the experiment)**: PEFT activation (median
improvement % vs HEFT clearly deviating from 0) should appear only under heterogeneous
conditions; across all homogeneous conditions, the PEFT-vs-HEFT median improvement % should be
close to 0 and tie-dominant.

**Verdict: partially confirmed, the conclusion must be narrowed (see Section 2.3).**

### 2.1 CLASSIC_DAX: confirmed in direction

- Homogeneous (the 20 main-block conditions): at vm≥8 the PEFT median improvement % is ≤0.03% in
  every case and tie-dominant (vm8: 2/2/1, vm16: 0/3/2 dominant, vm32: 1/3/1 dominant), so the
  prediction holds. **vm4 is the exception**: in the endpoint/wide/fast tiers PEFT reaches
  +1.665% (3 wins, 1 tie, 1 loss) and is not tie-dominant, so the prediction does not fully hold.
- Heterogeneous (fat-tree-constrained, against the homogeneous vm8/16 controls): PEFT shows a
  clear **negative** deviation: vm8 HET_MILD −2.454% (**0/0/5, all negative**), vm8 HET_STRONG
  −2.215% (1/0/4), vm16 HET_STRONG −1.605% (**0/0/5, all negative**); the matching homogeneous
  controls are +0.028% / +0.000% (both tie-dominant). The smallest Holm-adjusted p = 0.125 (vm8
  HET_MILD, vm16 HET_STRONG) does not reach significance, but the all-negative pattern is
  directionally consistent.

**Mechanism interpretation (exploratory)**: the OCT lookahead is indeed "activated" under
heterogeneous MIPS, in that decisions no longer degenerate to HEFT equivalence, but the
activation takes the form of a **systematic negative deviation**: OCT estimates contention-free
bandwidth optimistically per VM, and the heterogeneity gap makes it over-prefer fast VMs, which on
this corpus is worse than HEFT's actual placement. This supplies the heterogeneous-side control
evidence for S5's degeneration finding: degeneration (homogeneous, vm≥8) and optimistic bias
(heterogeneous) are two faces of the same lookahead structure.

### 2.2 SYNTHETIC: the prediction does not hold

PEFT already deviates substantially under homogeneous vm4/8 (−6.36% ~ −15.98%), and the
heterogeneous values swing unordered within ±18%; with n=2 there is no inferential power.
Mechanism (exploratory): under homogeneous VMs, PEFT's rank_o takes the minimum communication
within the same VM (=0) while HEFT's rank_u takes the average communication across VMs (>0), so
the two orderings differ in the first place; synthetic layered DAGs have a high communication
share, and the ordering difference translates directly into a scheduling difference. "Homogeneous
means degenerate" therefore holds only for the classic DAX corpus and cannot be generalized.

### 2.3 Narrowed conclusion

> On this study's exploratory corpus: (a) for CLASSIC_DAX at vm≥8, homogeneous degeneration of
> PEFT holds and heterogeneous activation holds, but the activation is negative in direction
> (optimistic bias) and is not significant after Holm correction; (b) the strong form of the
> prediction ("only" under heterogeneous conditions, "all" homogeneous conditions close to 0 and
> tie-dominant) does not hold: both the vm4 classic DAGs and the synthetic DAGs show homogeneous
> deviations. Any value judgment about the OCT lookahead must state both the corpus and the
> VM-count boundary.

## 3. Response surface I: homogeneous main block (median improvement % vs LOCAL_HEFT)

### 3.1 CLASSIC_DAX (5 DAGs; HEFT median makespan: vm4≈39.9h → vm32≈11.6h, endpoint)

| VM | Candidate | endpoint | ft-constrained | ft-mid | ft-wide | ft-fast |
|---:|:--|---:|---:|---:|---:|---:|
| 4 | CPOP | +0.002 | +0.012 | +0.003 | +0.002 | +0.002 |
| 4 | PEFT | +1.665 | +0.037 | +0.068 | +1.665 | +1.665 |
| 8 | CPOP | −0.015 | −0.015 | −0.015 | −0.015 | −0.015 |
| 8 | PEFT | +0.000 | +0.028 | +0.000 | +0.000 | +0.000 |
| 16 | CPOP | −0.015 | +0.091 | −0.015 | −0.015 | −0.015 |
| 16 | PEFT | −0.000 | +0.000 | −0.000 | −0.000 | −0.000 |
| 32 | CPOP | +0.000 | +0.170 | −0.000 | +0.000 | +0.000 |
| 32 | PEFT | +0.000 | +0.000 | +0.000 | +0.000 | +0.000 |

### 3.2 SYNTHETIC (2 DAGs, descriptive; HEFT median makespan: vm4 endpoint≈0.22h, ft-constrained≈0.95h)

| VM | Candidate | endpoint | ft-constrained | ft-mid | ft-wide | ft-fast |
|---:|:--|---:|---:|---:|---:|---:|
| 4 | CPOP | +14.911 | +15.066 | +14.967 | +14.911 | +14.911 |
| 4 | PEFT | −7.602 | −15.117 | −15.984 | −7.602 | −7.602 |
| 8 | CPOP | +2.368 | +5.224 | +5.620 | +2.369 | +2.368 |
| 8 | PEFT | −6.362 | −11.796 | −11.842 | −6.362 | −6.362 |
| 16 | CPOP | −12.852 | −15.244 | −14.781 | −12.857 | −12.852 |
| 16 | PEFT | 0 (all tied) | 0 (all tied) | 0 (all tied) | 0 (all tied) | 0 (all tied) |
| 32 | CPOP | +0.127 | +0.019 | +0.077 | +0.127 | +0.127 |
| 32 | PEFT | 0 (all tied) | 0 (all tied) | 0 (all tied) | 0 (all tied) | 0 (all tied) |

Bandwidth-axis reading (CLASSIC_DAX): only **vm4** is bandwidth-sensitive. PEFT drops from
+1.665% at endpoint/wide/fast to 0.037% at constrained and 0.068% at mid (slow links flatten its
ordering gain); at vm≥8, the full 0.125→5.0 MB/s bandwidth range has almost no effect on
CPOP/PEFT gains. VM=32 also switches to the k=8 fat-tree (see the retention notes in Section 6).

## 4. Response surface II: heterogeneity block (fat-tree-constrained, median improvement %)

### 4.1 CLASSIC_DAX (the homogeneous control is the HOMOGENEOUS condition with the same VM count and network)

| VM | Heterogeneity | HEFT median | CPOP med% (w/t/l) | PEFT med% (w/t/l) | PEFT Holm p |
|---:|:--|---:|:--|:--|---:|
| 8 | HOMOGENEOUS | 21.75h | −0.015 (2/0/3) | +0.028 (3/1/1) | 1.000 |
| 8 | HET_MILD | 27.39h | −0.070 (2/0/3) | **−2.454 (0/0/5)** | 0.125 |
| 8 | HET_STRONG | 24.46h | +0.000 (2/1/2) | −2.215 (1/0/4) | 0.750 |
| 8 | HET_EXTREME | 32.86h | −0.127 (2/0/3) | −0.358 (2/0/3) | 1.000 |
| 16 | HOMOGENEOUS | 14.69h | +0.091 (3/0/2) | +0.000 (1/2/2) | 1.000 |
| 16 | HET_MILD | 15.75h | −0.040 (1/1/3) | +0.000 (3/0/2) | 1.000 |
| 16 | HET_STRONG | 22.07h | −0.058 (2/0/3) | **−1.605 (0/0/5)** | 0.125 |
| 16 | HET_EXTREME | 16.67h | −0.030 (1/0/4) | −0.000 (1/2/2) | 1.000 |

CPOP is insensitive to heterogeneity (all ≤0.13%); PEFT's negative deviation is concentrated in
HET_MILD/HET_STRONG, and HET_EXTREME converges again instead (under an extreme gap the fast-VM
choice becomes trivial and the OCT disagreement shrinks; descriptive observation).

### 4.2 SYNTHETIC (2 DAGs, purely descriptive)

| VM | Heterogeneity | HEFT median | CPOP med% (w/t/l) | PEFT med% (w/t/l) |
|---:|:--|---:|:--|:--|
| 8 | HOMOGENEOUS | 0.75h | +5.224 (1/1/0) | −11.796 (0/1/1) |
| 8 | HET_MILD | 1.13h | +5.368 (1/1/0) | +12.716 (1/0/1) |
| 8 | HET_STRONG | 1.18h | −4.955 (1/0/1) | −15.605 (1/0/1) |
| 8 | HET_EXTREME | 1.18h | +7.740 (2/0/0) | +18.604 (2/0/0) |
| 16 | HOMOGENEOUS | 0.65h | −15.244 (0/1/1) | 0 (0/2/0) |
| 16 | HET_MILD | 1.02h | +0.106 (1/1/0) | +5.744 (1/1/0) |
| 16 | HET_STRONG | 1.01h | +8.084 (1/0/1) | +14.247 (2/0/0) |
| 16 | HET_EXTREME | 0.99h | −3.356 (0/1/1) | −4.026 (0/1/1) |

The effect of heterogeneity on the synthetic DAGs swings unordered within ±18% across
conditions; with n=2 the sign test has no resolving power, so this constitutes no inference.

## 5. Summary of descriptive points

1. **Bandwidth axis**: under CLASSIC_DAX homogeneous conditions, bandwidth affects only vm4 PEFT
   (+1.67% → +0.04~0.07%); at vm≥8 the gain is ≈0 in every bandwidth tier. CPOP is insensitive
   to bandwidth in all conditions.
2. **VM-count axis**: in CLASSIC_DAX the HEFT median makespan falls monotonically from 39.9h at
   vm4 to 11.6h at vm32; PEFT degeneration reaches its limit at vm≥16 (all ties/±0.00%); CPOP
   rises slightly with VM count on the constrained network (vm16 +0.091%, vm32 +0.170%, 3/2/0),
   so the critical-path assumption remains barely usable at large VM counts.
3. **Heterogeneity axis**: on CLASSIC_DAX, PEFT deviates negatively (MILD/STRONG, the 0/0/5
   all-negative pattern) while CPOP is robust; SYNTHETIC is unordered (see 4.2).
4. **SYNTHETIC and CLASSIC_DAX behave differently by source**: the communication structure of the
   synthetic layered DAGs makes the rank_o vs rank_u difference visible in the schedule (Section
   2.2); not pooling across populations is a hard requirement, not a formality.

## 6. Retention notes (consistent with PROTOCOL.md)

- This study is a **descriptive response surface** and makes no causal attribution; all inference
  is inference over an exploratory selected corpus, not over a random workload population.
- The VM=32 conditions also switch the fat-tree from k=4 to k=8 (k³/4=16 < 32), so the topology
  and the host pool change with them; the vm32 response-surface values contain a topology
  confound and are descriptive only, not attributable to the VM count itself.
- The SYNTHETIC layer has only 2 DAGs per condition and the sign test has no resolving power; the
  10 rows whose p-value column reads "-" are all ties (the SYNTHETIC vm16/32 PEFT homogeneous
  cells, with no non-tied pair, so p is undefined) and are treated as "no evidence".
- One deterministic run per cell (seed 11): there is no replicate variance estimate; improvement %
  is a single-seed point estimate.
- The negative activation of OCT is an in-corpus observation (5 classic DAGs) without external
  corpus calibration; the ready-list discipline fix does not change homogeneous behavior or the
  paper's worked example (frozen evidence re-validated at 504/126, PASSED), but it does change
  PEFT semantics under heterogeneous conditions, which is orthogonal to the R12 frozen conclusion
  (homogeneous degeneration).

## 7. Reproduction

```bash
mvn -pl :workflowsim -am install -DskipTests -q
mvn -pl :workflowsim-workbench exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full $PWD/datasets $PWD/output/<new-directory> sensitivity-r13"
mvn -pl :workflowsim-workbench exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="$PWD/output/<new-directory>/network-study.json"
# expected output: NETWORK_STUDY_VALIDATION PASSED runs=546
```
