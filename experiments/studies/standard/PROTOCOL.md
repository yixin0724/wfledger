# Standard Study Protocol (`standard-study-v1`)

STD-V1 (2026-11-25). This protocol declares the standard experiment design of the platform: a heterogeneous
fleet with bounded spread, a controlled communication-to-computation ratio (CCR), workflow instances as matched
pairing units, paired non-parametric tests with multiplicity control and effect sizes, and a fully declared
output header. It is a **design and a driver**; no full matrix has been executed under it. The driver is
[StandardStudy](<../../src/main/java/org/workflowsim/experiments/standard/StandardStudy.java>); the historical FGCS
drivers under `org.workflowsim.experiments.fgcs` are unchanged and remain historical.

## Why a new design

An independent audit of the historical FGCS drivers found: one deterministic run per cell on one instance per
family (no statistical unit); speeds `500 * ratio^i` (up to 16384x spread, fixed-capacity variants down to
0.18 MIPS); four VMs against DAG widths of 12-3310 (bin-packing dominated); energy on near-homogeneous fleets
with idle/max power 0.1 (contemporary servers draw about 0.6-0.7 of peak when idle); price super-linear in one
platform and sub-linear in another; uncontrolled CCR (CyberShake about 9, the other families at most 0.13);
deadline factors `{1.0 .. 2.0} x HEFT` (degenerate); and a periodic failure fixture. Each item below answers one
of these findings.

## Research question and statistical unit

Within **one decision layer** and **one evidence class**, does a candidate algorithm differ from the layer
baseline on makespan, energy, or cost, for a declared factor cell, across a population of workflow instances?

- **Pairing unit**: one workflow instance. All candidates of a factor cell run on the same instance, the same
  platform (including the instance's calibrated storage rate), and the same configuration except the algorithm.
- **Decision layers** (never pooled; `ExperimentPlan` also enforces one cost caliber per group):
  - `STATIC`: `SHARED_STORAGE_HEFT` (baseline), `SHARED_STORAGE_CPOP`, `SHARED_STORAGE_PEFT`.
  - `ONLINE`: `FCFS` (baseline), `READY_BATCH_MINMIN`, `READY_BATCH_MAXMIN`, `READY_BATCH_MCT`.
  HEFT is the baseline of the static layer because CPOP and PEFT are its published competitors (Topcuoglu et al.
  2002; Arabnejad and Barbosa 2014); FCFS is the baseline of the online layer because it is the decision-free
  dispatch the ready-batch heuristics improve on.
- **Evidence class**: exactly one of `WFGEN_SYNTHETIC` (`datasets/wfgen-standard/<recipe>/n<size>/*.json` or
  `datasets/wfformat/`) or `WFINSTANCES_REAL_DERIVED` (`datasets/wfinstances/`). An input whose path identifies
  the other class fails fast ([dataset rules](<../../../datasets/README.md>)). Inputs are WfFormat JSON.

## Fixed conditions

| Item | Value | Source / reason |
| --- | --- | --- |
| File system and data movement | `SHARED`, `LEGACY_WORKFLOWSIM_V1` | The SHARED envelope model of the [contracts](<../../../docs/algorithms/CONTRACTS.md>); required by the SHARED_STORAGE planners |
| Runtime conversion | `L(t) = runtime * 1000 MI/s` (reference MIPS 1000, scale 1) | WfFormat seconds are measured at an unspecified speed; 1000 is the platform default |
| Kernel tick | `cloudSimMinEventIntervalSeconds = 0.001` s | The default 0.1 s inflates each dependency step by 1-2 ticks; WfGen tasks reach sub-second runtimes |
| Bounded-slowdown floor `tau` | 1 s, clamped fraction reported per row | 10 s (Feitelson et al. 1997) targets parallel jobs; 48-76% of Pegasus tasks run below 10 s, so at 10 s the metric mostly measures `wait/10` ([metrics card](<../../../docs/algorithms/METRICS_CARD.md>)); 1 s keeps most tasks unclamped while bounding near-zero runtimes |
| Clustering, overheads | NONE, none | Platform scope |
| Cost model | `CostModel.VM` | Per-VM linear price below |
| Seeds | root 20261125; deterministic without failures | Multiple seeds of a deterministic run are repetitions, not samples |

## Platform (`StandardPlatforms`)

- **Fleet sizes** `N in {8, 16, 32}`, one single-PE VM per host, SPACE_SHARED.
- **Speeds** `mips_i = 500 + 1500 * i / (N - 1)`, `i = 0..N-1`: slowest 500, fastest 2000, ratio exactly 4 for
  every `N`; mean exactly 1250 MIPS. Host PE capacity is `ceil(mips)` because the legacy `Pe.getMips()`
  truncates; the VM keeps its exact rate.
- **Price** linear: `cpuPerSecond = c * mips`, `c = 1e-3` abstract units per MIPS-second, billed continuously
  per simulated second of charged CPU time; memory/storage/bandwidth prices are zero. The price per million
  instructions is therefore identical on every VM, so cost differences come from scheduling (idle-free
  execution time, envelope input reading), not from a price curve. Abstract units, not a cloud price list.
- **Power** linear utilisation model: `P_max = 0.1 W/MIPS * mips` (50-200 W), `P_idle = r * P_max` with `r` a
  parameter, default 0.6 (Fan, Weber, Barroso, ISCA 2007; Beloglazov et al., FGCS 2012 use 0.7). Energy counts
  envelope input reading at `P_max` (platform convention, metrics card).
- **Optional DVFS tiers**: the six AMD Opteron 2218 levels of Lee and Zomaya (IEEE TPDS 22(8), 2011), volts
  `{1.30, 1.25, 1.20, 1.15, 1.10, 1.05}` at GHz `{2.6, 2.4, 2.2, 2.0, 1.8, 1.0}`. Tier `j` runs at
  `mips * f_j / f_0`; busy power `P_idle + (P_max - P_idle) * (V_j/V_0)^2 * (f_j/f_0)` (dynamic power
  proportional to `V^2 f`); idle power stays at the default tier. The default tier equals the nominal MIPS, so
  the uniform default-tier rule (FGCS-R5) holds. The table was confirmed against its reproduction as Table 3 of
  Xing et al., Mathematics 11(9):2126, 2023, which cites Lee and Zomaya.

## Factors

| Factor | Levels | Layer |
| --- | --- | --- |
| VM count | 8, 16, 32 | both |
| CCR | 0.1, 0.5, 1, 2, 5, 10 | both |
| MTBF / mean task (`rho`) | 10, 20, 100 (`lambda * E[task] = 0.1, 0.05, 0.01`) | ONLINE only (the SHARED_STORAGE planners require failures disabled) |
| Deadline level `k` | 0.1, 0.3, 0.5, 0.7, 0.9 | deadline-aware studies (threshold only, see below) |

**CCR** (`CcrCalibrator`): `B` = sum over tasks of real-input MB (decimal; an input also declared as an output of
the same task is not real, `FileItem.isRealInputFile`), `compute` = sum of `L(t) / 1250`, `CCR(R) = (B / R) /
compute`; the storage rate is solved in closed form `R = B / (CCR * compute)` and is a finite positive double
(the SHARED rate was widened from `int` to `double` for this purpose; see
[reproducibility](<../../../docs/experiments/REPRODUCIBILITY.md>)), so the achieved CCR equals the target up to
binary64 rounding and is reported per row. The per-VM integer-MI truncation `floor(mips * inputSeconds)` of the
runtime envelope is excluded from the definition (it depends on the mapping and is below one MI per task). A
workflow without real input bytes cannot reach any positive CCR and fails fast.

**Failures** (`StandardFailureModel`): per-VM independent Weibull inter-arrival processes, shape 0.78 (Schroeder
and Gibson, DSN 2006 / IEEE TDSC 2010 report shape 0.7-0.8 for node inter-failure times; 0.78 as used by Chen et
al., IEEE TCC 2016), scale `rho * E[task] / Gamma(1 + 1/0.78)` with `E[task]` the mean compute seconds at 1250
MIPS, `FAILURE_VM` mode on every VM and depth, NOOP retry, retry budget 50 per task (fail-fast safety bound).
With failures the design is `COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN`; replications of one (cell, instance) are
averaged before pairing.

**Deadline** (`DeadlineInterpolation`): `D(k) = MS_fast + k * (MS_slow - MS_fast)`; `MS_fast` is the
`SHARED_STORAGE_HEFT` makespan on the tiered fleet at the default tier, `MS_slow` the same mapping with every
compute Job at the lowest tier (`MIN_POWER`; the mapping identity is checked and fails fast). Not rounded: it is
a study threshold compared with observed makespans, not the integer `SimulationConfig.deadline` observation.

## Metrics per row (one row per factor cell x instance x candidate)

`makespanSeconds` (terminal clock); `slrIoInclusive` (the platform's I/O-inclusive critical-path SLR, null
outside its controlled scope, e.g. with failures); `speedupVsSerialOnFastest = S / makespan` with
`S = sum_t (L_t + floor(2000 * in_t)) / 2000` (every task serially on the fastest VM with its SHARED read;
stage-in and ticks excluded); `meanWaitingSeconds` (ready-to-start); `meanBoundedSlowdown` at `tau = 1 s` and
`boundedSlowdownClampedFraction`; `meanVmUtilization` and `inputReadShareOfBusyTime`; `costUnits`;
`energyJoules`; `retryAmplificationRatio`; `storageRateMbPerSecond` and `ccrAchieved`.

## Statistics

For each factor cell and each tested metric (`makespanSeconds`, `energyJoules`, `costUnits`), every non-baseline
candidate is compared with the baseline over the instances: paired Wilcoxon signed-rank on `candidate -
baseline` (`PairedWilcoxonSignificance`: zeros dropped, exact distribution with average ranks up to 25 non-zero
pairs), Holm step-down within the family of non-baseline candidates of that cell and metric
(`HolmStepDownCorrection`, alpha 0.05; unavailable tests consume budget). Effect sizes (`PairedEffectSize`):
median per-instance ratio, matched-pairs rank-biserial correlation (Kerby 2014; the effect size of the
signed-rank test), and Cliff's delta (unpaired dominance, for comparability). Holm is not applied across cells or
metrics; a study claiming across cells must declare that family before execution. With fewer than six pairs the
two-sided exact test cannot reach 0.05; the corpus size must be fixed before execution.

## Outputs

A new or empty directory receives `standard-study.json` (header, rows, comparisons),
`standard-study-rows.csv` and `standard-study-comparisons.csv` (six `#` header lines: protocol ID, execution
semantics, git commit, corpus index hash, layer and evidence class, constants), and `campaign/` with the
per-run manifest/metrics/events evidence and the validated campaign index. The header records the protocol ID,
`WORK_CONSERVING_TASK_EXECUTION_V3`, the git commit and dirty flag, every constant, the corpus list with
SHA-256 per instance, and the corpus index hash (SHA-256 of `<sha256>  <file name>\n` lines in ascending path
order). Any failed run, incomplete workflow, or pairing violation aborts the study.

Command (smoke only; a full matrix is a separate, declared execution):

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.standard.StandardStudy \
  '-Dexec.args=/absolute/new-output STATIC WFGEN_SYNTHETIC 8 1 /absolute/datasets/wfformat/montage/n100/montage-100-000.json'
```

## What the design supports and what it does not

Supports: same-layer, within-evidence-class comparisons of the declared candidates under the declared model,
with instance-level paired inference, multiplicity control within a cell, effect sizes, and factor-wise
descriptions of how CCR, fleet size, and failure rate change those comparisons.

Does not support: any production calibration (speeds, prices, power, storage rates, and failure scales are
declared model constants, not measurements); generalisation beyond the sampled corpus and evidence class;
comparisons across decision layers or evidence classes; inference from seeds of a deterministic run; claims of
equivalence from non-significance; a Topcuoglu (compute-only) SLR; DVFS-planner comparisons (the deadline
references are provided, the energy-aware planner matrix is not part of v1).

## Verification

Unit tests with hand derivations: [StandardPlatformsTest](<../../src/test/java/org/workflowsim/experiments/standard/StandardPlatformsTest.java>),
[CcrCalibratorTest](<../../src/test/java/org/workflowsim/experiments/standard/CcrCalibratorTest.java>),
[DeadlineInterpolationTest](<../../src/test/java/org/workflowsim/experiments/standard/DeadlineInterpolationTest.java>),
[StandardFailureModelTest](<../../src/test/java/org/workflowsim/experiments/standard/StandardFailureModelTest.java>),
[PairedEffectSizeTest](<../../src/test/java/org/workflowsim/experiments/standard/PairedEffectSizeTest.java>); end-to-end
[StandardStudySmokeTest](<../../src/test/java/org/workflowsim/experiments/standard/StandardStudySmokeTest.java>)
(2 instances x 2 algorithms x 1 cell: output structure, CCR identity, speedup identity, pairing invariants,
cross-layer and mixed-evidence rejection).
