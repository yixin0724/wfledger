# DVFS Dimension

This document specifies the DVFS (dynamic voltage and frequency scaling) dimension: per-VM
frequency-tier tables, per-Job tier assignment at admission, the extended energy conservation
law, the frequency policies, and the audit and rerun surfaces. Energy accounting is specified in
the [energy dimension](<ENERGY_DIMENSION_DESIGN.md>) and carbon in the
[carbon dimension](<CARBON_DIMENSION_DESIGN.md>).

## 1. Primary-source survey: how the field models frequency control

### 1.1 The canonical power-frequency law

The dynamic power dissipated by a CMOS processing element is:
```
P_dynamic = a · C · V² · f
```
where V is the supply voltage, f the clock frequency, and a·C a technology constant
([Cotes-Ruiz et al., PLOS ONE 12(1), 2017](<https://doi.org/10.1371/journal.pone.0169803>)).
Since V scales with f (lower voltage implies lower selectable frequency), dynamic power
is effectively **cubic in frequency** — a halved frequency draws roughly 1/8 the dynamic
power. This nonlinearity is the entire physics of why DVFS saves energy at all.

### 1.2 WorkflowSim-DVFS (Cotes-Ruiz et al.) — the closest prior extension

The nearest prior work extends the ORIGINAL WorkflowSim with five Linux DVFS
governors (Performance, Powersave, Userspace, On-demand, Conservative) organized as:

| Governor | Strategy | Realism claim |
|---|---|---|
| Performance | Static max frequency | Upper bound on speed, lower bound on efficiency |
| Powersave | Static min frequency | Lower bound on speed, upper on efficiency per Job |
| Userspace | User program selects | Research extension point |
| On-demand | Utilization threshold | Real production default (most Linux distros) |
| Conservative | Gradual threshold-based | Less aggressive than On-demand |

Their power model is "computing-plus-communication": per-Job computing cost uses the
P(aCV²f) law evaluated at the governor-chosen frequency; reconfiguration and network
costs are added; validated on real Intel Core 2 Quad Q6700 measurements and
Montage/Inspiral/Sipht traces at 25–1000 Jobs. Their published savings range is
**5–30%** vs the Performance governor baseline.

It has no independent energy audit, no conservation-law invariant, no carbon accounting,
and no per-Job frequency assignments in its evidence.

### 1.3 The broader DVFS-workflow literature

| Paper | Venue | Model semantics |
|---|---|---|
| Mei et al. (arXiv 2104.00486) | Energy-aware, deadline-constrained | DVFS-enabled heterogeneous clusters; slack-based stretching: tasks with schedule slack can be stretched to lower frequencies without extending the makespan; EDP is the optimization target |
| Cao et al. (referenced in Cotes-Ruiz) | DVFS DAG optimum-frequency | No governor disclosure, no source available |
| Guérout et al. | CloudSim-DVFS | DVFS into CloudSim with DAG support — but no failure model, no reconfiguration cost, no network cost |
| DEFT (arXiv 2608.02122, 2025/26) | Multi-GPU runtimes | Joint task placement and DVFS — GPU-specific, but the frequency-tier table is the same structure |
| Liu et al. (PMC 2025) | Heterogeneous multicore edge | Frequency tiers as per-core P-states; multi-core DVFS is outside the current scope |

The **P-state table** (frequency, voltage → MIPS, static-idle power, dynamic-max power)
is the universal schema across all these works. Typical tier counts in practice:
3–5 discrete frequencies per processor (Intel SpeedStep, AMD PowerNow\!).

### 1.4 Execution semantics

The platform runs SPACE_SHARED one-Job-at-a-time per VM with a binary
utilization bit (u∈{0,1}). DVFS means each Job gets an **assigned frequency tier**
that slows it (work_work/f_i takes longer) while drawing less dynamic power.
The time-power duality:

- **Lower f**: Job takes longer, draws less dynamic power, but exposes the fleet to
  MORE seconds of idle energy (P_idle × extended makespan).
- **Higher f**: Job finishes faster, draws more dynamic power (cubic), but idle
  energy accrues for fewer seconds.
- **Break-even ruling**: stretching pays only when the dynamic savings
  (ΔP_dynamic × original_T) EXCEED the idle extension cost
  (P_idle × ΔT_extension). This is where slack-based stretching finds its liberty:
  if the Job has schedule slack (its original completion is well before the
  workflow's critical-path makespan), the extension costs NOTHING (other VMs keep
  working) — the stretched Job lands in the slack pocket and only the dynamic
  savings count.

The contract:

1. **Assignment at Admission**: each compute Job receives exactly ONE frequency
   tier at the moment it is dispatched to a VM — the tier is fixed for the Job's
   full duration. NO dynamic mid-Job frequency switching. This preserves the
   one-Job-per-VM discipline and makes each Job's [start, finish) interval a
   constant-power rectangle — the auditable structure the energy conservation
   law already expects.
2. **Default tier**: the FIRST tier in the VM's list is the default (unassigned
   Jobs run at the declared default). We do NOT silently "pick the fastest."
3. **The extended conservation law**:
   ```
   E_vm = P_idle(default) × T_sim + Σ_tiers [ (P_max(t) − P_idle(default)) × T_busy(t) ]
   ```
   where T_busy(t) is the total time VM spent executing at tier t (each busy
   interval is attributed to its assigned tier); §2.3 gives the exact form. With a
   single tier it reduces to the flat law.
4. **Idle power at the default tier**: when the VM is NOT executing, it draws
   the DEFAULT tier's idle power (not a mixed-frequency average), because idle
   power is drawn at the parked frequency.

### 1.5 The carbon interplay

Both carbon models (intensity multiplier and green-budget excess) ride on the
energy chain with NO new physics:
- Intensity: grams per tier = E(tier)/3.6e6 × I — same multiplication, now on
  per-tier-conserved energy.
- Budget: fleet timeline becomes a per-tier fleet-power curve — P_fleet(t) now
  sums each active VM's ASSIGNED tier power rather than a uniform dynamic term.
  The integration is identical; only the per-instant fleet power changes.

The end-to-end verdict on "is lower frequency always greener carbon?": NOT
necessarily — this is exactly the break-even ruling above, now with two
multipliers (energy AND carbon) both following the same conserved energy.
If stretching extends the makespan, the carbon budget model may see MORE total
excess if the longer timeline crosses into browner windows. The published
savings range (5–30%) is for average workloads with slack; DVFS savings are not
guaranteed.

## 2. Architecture

### 2.1 Data model

```java
PlatformProfile.VmSpec:
    + List<FrequencyTier> frequencyTiers    // default empty = single-tier mode

FrequencyTier:
    + double mips              // speed at this frequency (MIPS, must be > 0)
    + double idlePowerWatts    // static draw at this P-state (≥0, finite)
    + double maxPowerWatts     // fully active draw at this P-state (≥ idle, finite)
```

The tier list is the P-state table. Its FIRST entry is the default.
Validation: tiers MUST be sorted by descending mips (fastest first — the
workstation-native convention); power values must satisfy the same
idle ≤ max constraint as the flat power fields.

### 2.2 Assignment model

- **Config-level**: SimulationConfig gains an optional `frequencyPolicy` —
  the built-in policies are `DEFAULT` (every Job runs at the first tier), `MIN_POWER` (every Job runs at the LAST/smallest tier),
  `EDP_HEURISTIC` (each Job selects the tier minimizing EDP = E_tier ×
  estimated_T_tier, computed at dispatch with the Job's declared MI work). Its local energy
  estimate is the busy rectangle `P_max(tier) × T_tier`, so the choice minimizes
  `P_max(tier) × T_tier²` and does not depend on the dispatch clock. Fleet idle exposure is accounted exactly by §2.3, not by this
  per-Job estimate.
- **Speed basis**: planners and online policies rank VMs by nominal MIPS, while DEFAULT runs
  the first tier. The runner therefore requires one common ratio of default-tier MIPS to nominal MIPS
  across the fleet (an untiered VM has ratio 1); a VM-dependent ratio would silently reorder the fleet.
- **Scheduling-time API**: the active scheduling policy can elect a per-Job tier at
  dispatch through `org.workflowsim.scheduling.FrequencyTierSelector`; the session
  policy is the fallback. The selector's output is validated against the declared table
  by mips identity, and a null or undeclared tier fails fast.
- **Manifest-level**: each JobOutcome records its assigned `tierMips`, and the platform
  section serializes the tier table, so the evidence carries the full per-Job assignment.

### 2.3 Extended conservation law (the invariant)

```
E_vm = P_idle(default) × T_sim + Σ_tiers [ (P_max(tier) − P_idle(default)) × T_busy(tier) ]
```
Idle power is charged at the default tier over the whole simulation, and each busy
interval adds its assigned tier's dynamic draw above that idle floor, so a busy second
draws `P_max(tier)` in total. Without tiers this is the flat law
`P_idle × T_sim + (P_max − P_idle) × T_busy`.

`peakPowerWatts` is a declared nameplate constant, not the parked-tier power or an observed
time-series maximum. Each tiered VM contributes `max_tier P_max(tier)`, so the fleet value remains
an upper bound even when a lower-MIPS tier declares a higher maximum power than the default tier.

### 2.4 Audit recompute

The manifest platform section serializes per-VM `frequencyTiers` as
`{mips, idlePowerWatts, maxPowerWatts}` triples in declared (descending) order; the key is
omitted when the table is empty. A tiered VM keeps its flat `idlePowerWatts`/`maxPowerWatts`
at `0.0`, and tiered platforms count as *powered* both in Java
(`hasPowerModel() || hasFrequencyTiers()`) and in the Python `powered` predicate.

The Python auditor groups each Job's `[start, finish)` interval by its `tierMips` (union per
tier); Jobs without `tierMips`, including the stage-in envelope, run at the default tier. It
verifies per-VM and total energy under the per-tier law, and recomputes task windows at the
assigned tier speed (`WorkflowDatacenter.updateTaskExecTime` uses the same rule). When every
busy second is at the default tier, the per-tier law and the all-at-default formula coincide;
otherwise the auditor records both values and a `nonDefaultTierBusyObserved` flag in `notes`
(`dvfs-tier-accounting-observation`) and asserts only the per-tier law.

Carbon follows with no additional recompute: intensity grams multiply the per-tier conserved
energy, and the budget model's fleet power is Σ parked VMs at default-tier idle plus each active
compute Job at `P_max(its tier) − P_idle(default)`, with flat VMs keeping their `max − idle`
contribution in a mixed fleet (`CarbonAccounting.budgetModel`). The tiered branch engages only
when at least one VM declares a table.

`ManifestConfigRebuilder` round-trips `platform.vms[].frequencyTiers` and
`configuration.frequencyPolicy`, so tiered bundles rebuild to the recorded platform and rerun
`IDENTICAL_CORE`; a malformed recorded table (non-descending mips, flat power alongside a
table, missing `mips`, unknown policy) is `RECONSTRUCTION_REJECTED`. `configuration.frequencyPolicy`
is written only when the platform declares tiers or the policy is not `DEFAULT`.

### 2.5 Workbench JSON surface

```json
"platform": {
  "vmMips": [...],
  "frequencyTiers": [
    {"mips": 2000, "idlePowerWatts": 250, "maxPowerWatts": 400},
    {"mips": 1000, "idlePowerWatts": 220, "maxPowerWatts": 280},
    {"mips": 500, "idlePowerWatts": 210, "maxPowerWatts": 240}
  ]
}
```
Validation: mips strictly descending; power pairs valid; when frequencyTiers
declared, the plain idlePowerWatts/maxPowerWatts fields MUST NOT also be
declared (the first tier REPLACES them — one source of truth).

`frequencyTiers` is one uniform table applied to every VM; each entry is validated by
`PlatformProfile.FrequencyTier` and the order by `VmSpec`. A declared but empty array is
rejected; `null` and absence mean no tiers. `frequencyPolicy` resolves to
`SimulationConfig.FrequencyPolicy` (absent means `DEFAULT`; unknown labels are rejected), and a
non-`DEFAULT` policy without tiers is rejected, mirroring the runner. `carbonWindows` are outside
the Workbench surface.

### 2.6 Registry declaration surface

```java
AlgorithmDeclaration:
    + boolean dvfsAware (default false)
    + boolean frequencyTiersRequired (default false)
```
Implication lattice: frequencyTiersRequired ⇒ dvfsAware (same as the energy
and carbon lattices). Registry gate: a frequencyTiersRequired declaration
on a platform with no declared frequencyTiers → fail-fast citing the clause.

`frequencyTiersRequired` pulls `dvfsAware` up in the builder, as `powerModelRequired` pulls
`energyAware`. The registry track's power fact uses the same powered predicate (flat fields or a
tier table). The test-registry demonstrator `EDP_GREEDY` declares
`dvfsAware + frequencyTiersRequired + powerModelRequired` and assigns long Jobs to the last tier
and short Jobs to the default tier on FCFS placement; it is a demonstrator, not an optimizer.

The [standard benchmark](<../benchmark/STANDARD_BENCHMARK.md>) DVFS variant is the dimension's
comparison matrix.

## 3. Deliberate omissions

| Omitted | Reason |
|---|---|
| **Dynamic mid-Job frequency switching** | Breaks the one-rectangle-per-Job auditability; the literature's intra-Job P-states are a hardware reality but the evidence cannot represent mid-Job power transitions, so a tier is assigned at admission |
| **Heterogeneous per-VM non-uniform voltage tables** | Real per-VM voltages add a dimension without changing the per-Job rectangle; not modeled |
| **Multi-core DVFS** | Our SPACE_SHARED discipline is one-Job-per-VM single-PE; multi-core DVFS is a different execution model |
| **Thermal / green scheduling beyond carbon** | Thermal simulation is a separate physics; carbon IS our environmental dimension |
| **Reconfiguration cost** (Cotes-Ruiz's transition energy) | Transition energy (\~50μJ level) is 6+ orders of magnitude below the per-Job energy scale and below our simulator's time granularity; documented as excluded with the magnitude justifying it |
| **Exact EDP optimization** | The EDP_HEURISTIC policy is per-Job greedy (each Job independently picks its minimum-EDP tier at dispatch); the global-optimal EDP is an NP-hard cross-Job optimization — delegated to registered DVFS-aware algorithms |

## 4. DVFS Slack Allocation

`DVFS_SLACK` adds a planner/scheduler pair on top of the existing tier contract. The planner starts
from HEFT mapping and accepts a lower tier only when the complete schedule meets the deadline and
total energy strictly decreases. The registry keys are logical Task IDs, while the scheduler looks up
that Task through `Job.getTaskList()`; stage-in Jobs remain outside the compute-tier contract.
`DVFS_SLACK` is accepted as an explicit static dispatch label in the planning/scheduler combination
check and must have a completed planning result. This is a greedy slack-allocation subset, not a
claim of global DVFS optimality or a replacement for the existing DEFAULT/MIN_POWER/EDP policies.
