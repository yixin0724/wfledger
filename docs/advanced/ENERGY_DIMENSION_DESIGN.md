# Energy Dimension

This document specifies the energy dimension: the linear power model, the energy conservation
law, the energy metrics, the registry declarations, and the scope. Frequency tiers are specified
in the [DVFS dimension](<DVFS_DIMENSION_DESIGN.md>) and carbon accounting in the
[carbon dimension](<CARBON_DIMENSION_DESIGN.md>).

## 1. Literature survey

### 1.1 The canonical power model (Beloglazov et al.)

The linear power model — used by every downstream system we surveyed — was formalized in
[Beloglazov, Abawajy, Buyya, FGCS 28(5), 2012](<https://doi.org/10.1016/j.future.2011.04.017>)
(3790+ citations):
```
P(u) = P_idle + (P_max − P_idle) × u
E = ∫ P(u(t)) dt
```
In a discrete-event simulation where SPACE_SHARED VM-execution is binary (one Job at a time),
`u ∈ {0, 1}` — so the integral collapses to idle-and-active rectangles. This is the exact
same mathematics as our transfer byte-conservation law; our energy conservation law adds one
multiplication to fields the manifest already carries.

### 1.2 WorkflowSim-DVFS — the closest prior extension of WorkflowSim

[Cotes-Ruiz et al., PLOS ONE 12(1), 2017](<https://doi.org/10.1371/journal.pone.0169803>)
extended the original WorkflowSim with a computing-plus-communication power model, integrating
5 Linux DVFS governors (Performance, Powersave, Userspace, On-demand, Conservative). Their
power model per frequency level (`P = aC·V²·f`) is grounded in physical measurements of an
Intel Core 2 Quad Q6700. They added per-frequency MIPS, voltage, power tables; per-governor
state machines; intra-host DVFS reconfiguration costs; and network power. Their published
savings are 5-30%. **What they lack**: independent energy-audit (no Python recompute), no
energy conservation-law test, no frozen-benchmark evidence chain, no registry-based
energy-aware algorithm declaration. This is our differentiation surface.

### 1.3 Carbon-aware scheduling — the 2024/25 frontier

| Paper | Venue | Model | Reduction | Carbon source |
|---|---|---|---|---|
| [CASPER (Souza et al.)](<https://arxiv.org/abs/2403.14792>) | IGSC'23 | Spatial: multi-region LP optimization (K8s; 6 AWS regions) | up to 70% (web services) | Electricity Maps (hourly, real grids) |
| [CWM (Schweisgut, Benoit, Robert et al.)](<https://doi.org/10.1145/3754598.3754673>) | ICPP 2025 | Temporal: DAG fixed mapping + carbon-intensity(t); greedy + local search + DP-based mapping | 42% median over carbon-agnostic baseline | Declared green-energy windows per datacenter |

**Key structural finding**: all carbon-aware schedulers compute carbon as
`energy × carbon-intensity(location, time)`. The carbon intensity comes from external
(grid-operator) datasets. **No simulator has a carbon model built in** — it's always a
multiplier the researcher controls. Our declared-multipplier design is literature-aligned.

### 1.4 Energy-aware DAG/list scheduling variants

| Family | What they modify | Published range |
|---|---|---|
| EHEFT / GreenHEFT / IAEWS | rank formula → energy-weighted rank; insert → energy-cost insert | 10-25% energy reduction vs. HEFT, 0-15% makespan degradation |
| Multi-objective evolutionary (NSGA-II, GOA) | Pareto front (makespan, energy) | 15-30% Pareto-dominance |
| Energy-delay product (EDP) | `E × makespan` scalarized bi-objective | Standard |
| VM consolidation | pack onto fewer hosts, sleep remainder | 20-80% |

### 1.5 DVFS in workflow scheduling

- Published frequency-selection: slack-based (stretch non-critical tasks to lower frequency
  tier while keeping critical-path at max), governor-based (on-demand, conservative)
- **EDP = Energy × Makespan** is the standard combined objective
- Published savings: **5-30%**; reconfiguration overhead ~100 j (negligible)
- Conservative governor achieves the best energy-efficiency ratio under dependency slack

### 1.6 Competitive landscape

| Simulator | Power model | DVFS | Energy metrics | Independent audit | Carbon |
|---|---|---|---|---|---|
| WRENCH / SimGrid | pstates (per-freq V, f) + link power | Yes | per-host, per-link | No | No |
| CloudSim 3 | PowerModel hierarchy, avg per Host | No | Datacenter-level | No | No |
| WorkflowSim-DVFS (2017) | Eq(2) linear, per-frequency | Yes (5 governors) | Per-VM | No | No |
| **This platform** | Eq(2) linear (Beloglazov anchor) | Tier tables | Per-VM + total + EDP | **Python verifier** | **Declared intensity or budget** |

---

## 2. Architecture

### 2.1 The auditable energy extension

**Data model** (`VmSpec` fields)
```java
+ double idlePowerWatts;
+ double maxPowerWatts;
```

**Energy conservation law**
```
E_vm = P_idle × T_idle + P_max × T_active
E_total = Σ E_vm = Σ P_idle × T_sim + (P_max − P_idle) × T_active
```
where `T_sim` = `makespanSeconds` and `T_active` per VM is already `vmIntervals` in
the metrics — all existing fields. The Python verifier recomputes this identity from the
manifest's own data.

**Metrics** (the manifest `energyMetrics` section, computed by `EnergyAccounting`, not by
`SimulationMetrics`)
| Metric | Formula |
|---|---|
| `totalEnergyJoules` | conservation-law result |
| `meanVmEnergyJoules` | `E_total / vmCount` |
| `peakPowerWatts` | `Σ_vm P_nameplate(vm)` (fleet peak); flat VMs use `P_max`, tiered VMs use `max_tier P_max(tier)` |
| `energyMakespanProductJouleSeconds` | `E_total × makespan` (EDP) |

**No new event types.** `JOB_EXECUTION_STARTED / TASK_EXECUTION_MODELED / JOB_RETURNED`
already pin every utilization-flip instant.

**Algorithm registry**
```java
AlgorithmDeclaration:
+ boolean energyAware (default false)
+ boolean powerModelRequired (default false)
+ double energyObjectiveWeight (default 0)
```

**Combination matrix**: if power fields are declared, energy metrics are computed;
if not, `totalEnergyJoules=0` is a legally declared run. The
declaration is **all-or-none**: every VM declares a power source
(plain idle/max fields or frequency tiers) or none does. A mixed fleet is rejected by
`SimulationRunner` preflight, by `EnergyAccounting.requireAllOrNonePowered`, and by the
Python auditor, because booking the unpowered VMs at 0 J would silently understate fleet
energy.

**Python auditor**: recomputes `totalEnergyJoules` from vmIntervals + power fields;
or verifies `=0` when no power model is present.

## 3. Scope

| Exclusion | Reason |
|---|---|
| Thermal model | Adds thermodynamic simulation, not a declared model |
| Hardware calibration | Internal-validity tradition |
| Real carbon-intensity datasets | External-data dependency, can't be audited |
| Network/switch power | The platform simulates workflow execution, not infrastructure |
| VM migration energy | Platform placement discipline is preflight-pinned |

## 4. Energy-aware scheduling example

The registry-based comparison `FCFS` vs the registered `ENERGY_LPT_FIRST` provider
(longest-job-first, declares `powerModelRequired`) on a two-VM powered platform
(250 W idle / 400 W max each) and a submission-shuffled pure bag (40, 30, 95, 35 —
the dominant job submitted mid-bag):

| Arm | Makespan (s) | Total energy (J) | EDP (J·s) |
|---|---:|---:|---:|
| FCFS (built-in) | 125.21 | 92,621.5 | 1.160×10⁷ |
| ENERGY_LPT_FIRST (registered) | 105.21 | 82,621.5 | 8.693×10⁶ |

The conservation law closes the gap exactly: the energy difference (10,000 J) equals
the 20-second makespan difference times the fleet idle power (2 × 250 W). The
expectation is provable, not a hope: on a pure bag with uniform VMs LPT cannot lose to
submission order (Graham), and with the linear power model total energy is strictly
monotone in makespan when total busy work is workload-fixed (exactly true on this bag;
across other workloads busy time can drift slightly between policies, and that drift can
decide the energy ordering). Behavior anchor:
[EnergyAwareSchedulingComparisonIntegrationTest](<../../experiments/src/test/java/org/workflowsim/experiments/registry/EnergyAwareSchedulingComparisonIntegrationTest.java>).
This is a two-VM, one-bag demonstrator of the end-to-end energy-aware
path (registry gate → powered run → audited evidence) — not a general performance
claim about LPT on arbitrary DAGs.
