# Paper-Metrics Conventions Card (Metrics Writing Card)

> A one-page reference for citing this platform's metrics correctly when writing a paper or a report. Each row does three things: our conventions (formula / sample domain) → a one-sentence difference from the closest conventions in the literature → writing advice.
> Authoritative definitions are those of the [algorithm, execution, and metrics contract](<CONTRACTS.md>); this card concentrates only on "citation differences" and introduces no new semantics.


## Time Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `makespanSeconds` / `simulationEndSeconds` | The simulation termination clock | Literature makespan usually refers to the workflow completion span; our positive terminal-state tail (post-delay, V3 write-back) is folded into this value | State the "terminal-state clock" conventions; when comparing executor overhead, also report `terminalLifecycleTailSeconds` |
| `logicalTaskCompletionSeconds` | When all logical Tasks succeed, the maximum of the first successful envelope completion times | Differs from the previous conventions only by the tail; this metric is closer to the literature's makespan | Report both conventions and their difference; do not use the two names interchangeably |
| `terminalLifecycleTailSeconds` | Terminal state − logical completion | No direct counterpart in the literature | Cite it as the "write-back/wrap-up tail", not CPU |
| `computeJobOutcomeThroughputPerSecond` | Compute attempt count / terminal-state clock | The denominator includes the tail | It is enough to state the conventions |
| Kernel-tick inflation | Each release/completion is aligned to the CloudSim tick (`cloudSimMinEventIntervalSeconds`, default 0.1 s) plus its safety margin | Inflation is a fixed offset of about 1–2 ticks per dependency step, not a proportional error | Negligible for minute-scale Tasks; state the tick when Tasks are sub-second |
| `controlledSharedStorageScheduleLengthRatio` | `makespan / L`, where `L` is an **I/O-inclusive critical-path lower bound**: the model-generated stage-in Job on the fastest VM plus, along every path, each Task's minimum over VMs of compute seconds **plus its SHARED external-input read seconds** | Topcuoglu et al. (TPDS 2002) SLR divides by the sum of minimum *computation* costs on the critical path, without I/O or stage-in; ours is therefore smaller (larger denominator) on I/O-heavy workflows | Cite it as the "I/O-inclusive CP lower-bound SLR"; do not compare it numerically with a Topcuoglu SLR. The field name is kept for schema stability. Available only in the declared controlled scope |

## Waiting and Slowdown Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `wait_j` (precursor of the waiting mean) | `JOB_READY → start` (waiting after dependencies are ready) — **the platform's queueing measure** | The literature commonly reports pure submit→start queueing | In papers, write "waiting after ready"; it is the only queueing measure: see the next row for why the VM-queue fields are not one |
| `meanJobVmQueueWaitingTimeSeconds` / `meanJobResponseTimeSeconds` | VM submission→start and VM submission→finish of compute Jobs | Under the idle-only dispatch invariant (a Job is submitted to a VM only when that VM is idle, enforced fail-fast by `WorkflowDatacenter`) the VM submission instant is the start instant, so the queue wait is **identically zero** and the response **equals the run time** (`meanComputeJobRunTimeSeconds`). A configured `queueDelays` overhead does not change this: it delays the submit event itself (the `SCHEDULING_DECISION`/`JOB_DISPATCHED` `queueDelaySeconds` attribute), so it appears in decision→start and ready→start, never in submit→start | Do not report either as a queueing or response measure; they are kept only for schema stability. Workbench emits no response column |
| `meanComputeTrueSlowdown` (including median/quantile/maximum/success-sample variants) | `max((wait+exec)/max(exec,τ),1)`, on ready-baseline samples; `τ` = `boundedSlowdownThresholdSeconds`, a declared configuration value (default 10 s, finite and positive), recorded in the manifest configuration and echoed in the metrics | Bounded slowdown with the 10 s floor of Feitelson et al. (JSSPP 1997); cross-paper differences lie mainly in the floor value (1 s / 0.1 s variants) and in the numerator window (the literature mostly uses a submit baseline). With Pegasus workflows 48–76% of Tasks run shorter than 10 s, so at `τ = 10` their slowdown is `max(wait/10 + exec/10, 1)` and rankings can flip with `τ` (Sipht: FCFS ahead of HEFT at τ = 10 and 1, behind at τ = 0.1) | Always report `τ` and `boundedSlowdownFloorClampedFraction` (samples with `exec < τ` / samples) next to the value; a high clamped fraction means the metric mostly measures `wait/τ`. Prefer a sensitivity row at a second `τ` when ranking algorithms |
| p95 / median (each variant) | Nearest-rank `ceil(qn)` on ascending samples | A systematic difference exists between interpolation-based quantiles and nearest-rank (p95 of a small sample degenerates to the maximum) | State the conventions and keep them fixed; do not merge quantile values across papers |

## Counts, Utilization, and Fairness Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `logicalTaskAttemptCount` | Includes retry attempts | The literature may count per task instead | Give the definition when citing |
| `retryAmplificationRatio` | Attempt count / logical Task count | Not a failure rate | Include the formula when reporting |
| Success-rate family | A compute-attempt base rate and a logical-task rate coexist | The denominator choice is easy to confuse | State the denominator |
| `vmMetrics` | The union of completed Job intervals per VM / the terminal-state clock | Consistent with the busy/end common in the literature | Declare the interval composition, including stage-in/failures/retries |
| `totalInputReadSecondsInEnvelope` / `totalComputeOnlyBusySeconds` / `inputReadShareOfBusyTime` (+ per-VM `inputReadSecondsInEnvelope` / `computeOnlyBusySeconds`) | Read = Σ `effectiveDataStageSeconds` of the `JOB_EXECUTION_STARTED` events; compute-only = busy − read; share = read / busy (0 when busy is 0). Non-zero only for legacy/fixed-endpoint models, which fold the input read into the Job envelope; available only when the stream carries start markers (`inputReadInEnvelopeObservationCount`) | Literature utilisation is CPU utilisation; ours counts the envelope read as busy (CyberShake_50 under SHARED: ~89% of busy time is input reading) | Report the I/O share next to utilisation; state that busy time and utilisation include input-read seconds |
| `vmUtilizationJainFairnessIndex` | `(Σu)²/(nΣu²)`; empty/all-zero → 1.0 | Consistent with the literature's Jain formula | Cite after declaring the degenerate semantics |

## Data Transfer Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| DATA_STAGE_IN_MODELED family | Nominal estimates (contention-free isolated seconds, referenced bytes, real external inputs) | The literature often loosely calls this "traffic" | Distinguish nominal estimates from actual contended wall-clock; state that these are referenced bytes, not transferred bytes |
| Contended FCT / utilization | Precise accounting from the sidecar ledger | See the [network ledger contract](<../advanced/NETWORK_LEDGER.md#5-exact-derived-metrics>) | Cite the conventions of that contract |

## Energy Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `totalEnergyJoules` | **Envelope input-read seconds are charged at `P_max` as active time** (the decomposition above exposes them; the formula is unchanged). `Σ_vm [P_idle × T_sim + (P_max − P_idle) × T_busy,vm]` — per-VM rectangles of the linear utilization model, Beloglazov et al. FGCS 2012 Eq.(2), specialized to binary SPACE_SHARED (`u ∈ {0,1}`); `T_sim` = `makespanSeconds`, `T_busy` = that VM's `vmMetrics` modeled busy-interval seconds | The literature integrates `P(u) = P_idle + (P_max − P_idle) × u` over continuous utilization; with one-Job-per-VM execution the integral collapses to idle-and-active rectangles, so the same Eq.(2) yields exact, not approximate, accounting | State the binary specialization and the busy-interval domain together; the identity is recomputed from the manifest alone by the repository's Python auditor |
| `meanVmEnergyJoules` | `totalEnergyJoules / vmCount` (arithmetic mean over all declared VMs, idle-only VMs included) | The literature usually averages only serving hosts; ours includes every declared VM | State the denominator explicitly (fleet size, not busy-VM count) |
| `peakPowerWatts` | `Σ_vm P_nameplate(vm)` — flat VMs use `P_max`; tiered VMs use `max_tier P_max(tier)`; the fleet's nameplate peak is a declared platform constant | For an observed instantaneous maximum, it is an upper bound, not a measurement | Do not present it as a time-series power trace or substitute the default tier's `P_max`; only a homogeneous flat fleet degenerates to `vmCount × P_max` |
| `energyMakespanProductJouleSeconds` | `totalEnergyJoules × makespanSeconds` | The standard energy-delay product (EDP) of the DVFS literature; some papers use `E × D²` or weighted combinations | Name the exact product when comparing: EDP values under different objective forms are not interchangeable. When idle power is significant, `E ≈ (Σ P_idle) × makespan + …`, so `EDP ≈ c × makespan²` is makespan-dominated and adds little beyond makespan |

## Carbon Family

Conditional section: these metrics appear in a manifest's `carbonMetrics` only when a carbon model is declared; exactly one of the two models may be declared per run (mutual exclusion is enforced fail-fast). Both families ride the energy family's audited quantities — they add accounting, not physics.

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `totalCarbonGrams` | Intensity model: `Σ_vm E_vm[J] / 3.6e6 × I_vm[g/kWh]` — hand formula: each VM's linear-model joules (see `totalEnergyJoules` for the rectangle law) converted to kWh (`J / 3.6e6`) and multiplied by that VM's declared grid intensity (location-based, gCO₂/kWh) | CASPER-family spatial tools (Souza et al., IGSC'23) compute `service_time × carbon_intensity(region, hour)` from live hourly grid data; ours is a static per-VM declared intensity, an explicitly stated proxy | Name the intensity basis as declared-location-based; state that no live grid feed enters the simulation. Zero under the budget model |
| `meanVmCarbonGrams` | `totalCarbonGrams / vmCount` (arithmetic mean over all declared VMs, idle-only VMs included) | The literature usually reports region-level totals; ours keeps the per-VM denominator explicit | State the denominator explicitly (fleet size, not busy-VM count); zero under the budget model |
| `carbonMakespanProductGramSeconds` | `totalCarbonGrams × makespanSeconds` | A carbon-delay product in the spirit of EDP; no single standard form exists in the literature | Name the exact product when comparing; zero under the budget model |
| `totalCarbonExcessWattSeconds` | Budget model: `Σ_windows ∫ max(0, P_fleet(t) − greenWatts) dt` over the window∩segment, with `P_fleet(t) = Σ_vm P_idle + concurrent × (P_max − P_idle)` rebuilt from the compute-job intervals (v1 assumes a homogeneous active delta across the fleet) | CWM/CaWoSched-family temporal scheduling (Schweisgut et al., ICPP 2025) defines carbon as an abstract over-budget excess integral, not gCO₂; both agree that under-budget execution is zero-carbon | State the unit: watt-seconds of excess power, not grams; zero under the intensity model. Under-budget execution counting as zero-carbon must be stated when comparing with gram-based works |
| `windowExcessWattSeconds` | The budget family's per-window sub-map of `totalCarbonExcessWattSeconds`, keyed by window index; only windows with positive excess appear, and the values sum to the total | The literature reports a single aggregate objective; ours keeps the per-window breakdown so a third party can re-verify each window | Cite the window-declaration basis (declared renewable-availability scenario, not a forecast feed); the sum identity is recomputed by the repository's Python auditor |
| CaWoSched budget-report compatibility | Only for `CAWOSCHED_SLACK` / `CAWOSCHED_PRESSURE` on flat fleets: integrate to the configured deadline and sum each active Job's own `P_max(vm) − P_idle(vm)` | The timed planner already uses the full deadline and heterogeneous processor powers; other labels retain the legacy makespan horizon and first-VM dynamic delta | State the planning label, horizon, and power convention together; do not place generic v1 and CaWoSched excess values in one comparison without declaring this distinction |

## DVFS Family

Conditional annotations: these fields appear only when the platform declares a per-VM
`frequencyTiers` table (docs/advanced/DVFS_DIMENSION_DESIGN.md).
The dimension adds **no new physics** — the energy and carbon families above ride the
same conserved quantities; the rows below are the tier-attribution annotations that a
third party must state when citing them. Where a row says "identity only", the value is a
deterministic function of fields another row already defines — cite it as such, never as
an independent measurement.

| Metric / annotation | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| per-Job `tierMips` (evidence, not a metric) | One tier per compute Job assigned at admission (dispatch-to-VM); the tier is fixed for the Job's whole `[startTime, finishTime)` interval; bookkeeping envelopes (stage-in) carry no tier and account at the VM's *default* (first) tier | The DVFS-governor literature reports per-governor aggregates, not per-job evidence-assignments in an auditable evidence chain | State the assignment-at-admission semantics before any tier-mix comparison; the Python auditor reconstructs per-tier busy time from these keys alone |
| per-VM per-tier busy seconds (`perTierBusySecondsByVm` ledger; the manifest derives them from `tierMips` + job intervals) | Σ over each VM's job intervals grouped by assigned tier (union per tier; the sum must equal the VM's total modeled busy interval, 1e-9 s slack) | WorkflowSim-DVFS (Cotes-Ruiz) accounts per-governor computing cost per Job but does not carry a per-tier busy ledger cross-checkable against busy totals | When reporting a tier mix, give the fleet per-tier busy totals (Σ over VMs) and state the identity they must sum to |
| `fleetPerTierBusySeconds` | Fleet-wide Σ of the per-VM per-tier ledger, keyed by tier mips ascending — the tier-mix evidence the frozen DVFS benchmark reports per cell | The literature's "frequency distribution" is usually a utilization histogram, not MI-attributed busy seconds | Cite as declared-model busy attribution, not a hardware power-state residency trace |
| `totalEnergyJoules` under tiers | Same field as the energy family, computed by the extended law `E_vm = P_idle(default)×T_sim + Σ_tier[(P_max(tier) − P_idle(default)) × busy(tier)]` — degenerates exactly to the single rectangle law when every busy second is at the default tier (or no tiers are declared) | Cotes-Ruiz adds reconfiguration and network energy terms and validates against real Q6700 measurements; our v2 excludes reconfiguration cost by documented magnitude and rides the conserved-energy audit chain | State that the parked draw uses the default tier's idle and each busy rectangle its assigned tier max; recompute cost is explicitly out of range (design §3) |
| tier-speed task windows (`startTime`/`finishTime` of task outcomes) | Task duration = declared work / assigned tier speed (work is never rewritten; the Job cloudlet length carries the `vm.mips/tier.mips` scaling), asserted by the Python auditor's `effective-task-window`/`cpu-lower-bound` recomputes | Most DVFS simulators scale job length silently and report no cross-checkable task-level window identity | When comparing per-task timings, state the tier-speed basis; a v1-speed window next to a slow-tier claim is a kill in our auditor, not a rounding difference |
| `peakPowerWatts` under tiers | Same nameplate field as the energy family, with each tiered VM contributing `max_tier P_max(tier)` rather than the default tier's `P_max` | A parked/default tier determines idle draw, not the maximum power declared by the VM's P-state table | Keep this declared upper bound constant across frequency policies, including legal tables whose lower-MIPS tier has a higher `P_max` |
| `energyMakespanProductJouleSeconds` under tiers | Same field as the energy family (E × makespan) — identity only; the DVFS cells of the frozen benchmark rank this product, and per-Job greedy EDP is a *policy fill* for it, not its definition | The DVFS literature optimizes EDP (or ED²P) per job with slack knowledge; the platform's greedy is dispatch-local by design | Name the product's two factors and their conventions before comparing policies; EDP values under different objective forms are not interchangeable |
| `totalCarbonGrams` under tiers | Same field as the carbon family — the intensity model multiplies exactly the per-tier-conserved joules | DVFS carbon studies price energy per time-slot region mixes; ours is a declared uniform intensity riding the verified per-tier chain | Cite the intensity basis together with the tier energy law — carbon ratios equal energy ratios at a single uniform intensity |

## Citation Discipline (Template Sentences for the Performance Evaluation Subsection)

1. Together with every reported metric, give the conventions formula (or cite this card) and the sample domain (whether retries/failures/stage-in are counted).
2. For slowdown/waiting: declare the window baseline (ready vs submit), the denominator floor `τ` (`boundedSlowdownThresholdSeconds`, default 10 s), and the clamped fraction.
3. Cross-paper comparisons are limited to identical conventions and identical denominators; when citing "bounded slowdown", state the floor value.
4. Always state in the threats-to-validity section: all metrics are products of the declared simulation model, not measurements of a production system.

## Experiment Reading Map

The [baseline studies](<../experiments/BASELINE_STUDIES.md>) provide configurations in which
waiting time and makespan can rank algorithms differently, and in which energy-oriented policies
trade energy against makespan. A planner's compute-energy objective is not total fleet energy.
