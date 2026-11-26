# Carbon Dimension

This document specifies the two carbon accounting models (intensity multiplier and green-budget
excess), their metrics, audit, Workbench surface, and the `CARBON_WINDOW_DEFER` scheduler. Both
models are computed from the conserved per-VM energy of the
[energy dimension](<ENERGY_DIMENSION_DESIGN.md>).

## 1. Primary-source survey: the two carbon accounting models in the literature

The carbon-aware scheduling field does **not** agree on one carbon model — it uses two
structurally different accountings, and the platform implements both explicitly.

### 1.1 The intensity-multiplier model (CASPER family)

[CASPER (Souza, Jasoria, Chakrabarty, Irwin, Shenoy + Chalmers, IGSC'23)](<https://arxiv.org/abs/2403.14792>)
optimizes **spatially**: six AWS regions, hourly LP re-provisioning (PuLP), Traefik
hourly-weight load balancing, multi-objective cost
`α·(carbon) + (1-α)·(latency)` with capacity `c_j` and fleet cap `K`. Their carbon
accounting is a **multiplier on energy**:
```
carbon(request) = service_time × carbon_intensity(region, hour)
```
Sources: Electricity Maps hourly data (2022), workload from Wikimedia traces, latency from
Cloudping. Results: **25% reduction at 20 ms latency budget, 37% at 100 ms, up to 70%**
at 500 ms — spatial shifting trades latency for carbon monotonically.
The multiplier model is the accounting used by essentially all grid-data tools
(gCO₂/kWh × kWh = gCO₂).

### 1.2 The green-budget-excess model (CWM / CaWoSched family)

[CWM (Schweisgut, Benoit, Robert, Meyerhenke, ICPP 2025)](<https://arxiv.org/abs/2605.27652>)
optimizes **temporally** with a fundamentally different accounting. The platform is
declared a **green power budget per time interval**: horizon `[0,T)` divided into J
intervals `I_j=[b_j,e_j)`, each with green power budget `G_j` per time unit. The carbon
cost is the **excess fleet power above the budget**:
```
carbon(t) = max(0, P_total(t) − G_j(t))    for t ∈ I_j
CC = ∫₀ᵀ carbon(t) dt
```
With `P_total(t) = Σ_p P_idle^p + P_work^p·[p busy at t]`. Under-budget execution is
**zero-carbon** (renewables cover it); over-budget execution emits. The problem is
strongly NP-complete with **no constant-factor approximation** (single processor suffices);
CWM = per-interval processor-subset knapsack (DP) + HEFT-SL adaptation + local search;
**42% median carbon reduction over the best CaWoSched variant** at deadline = 2× the
carbon-agnostic makespan (CaWoSched itself −36% vs baseline; CaWoSched = the earlier
fixed-mapping+ordering shifter of [25] — our earlier survey cited this paper's 42/36%
correctly).
Key algorithmic semantics from the paper we adopt as design constraints:
- Deadlines interplay directly (soft tasks stretch across intervals to cheap windows)
- Heterogeneous per-processor `P_idle`/`P_work` are first-class inputs
- The budget model treats carbon as an **abstract excess-cost integral**, not gCO₂

### 1.3 Metrics conventions

Grid-data tools express intensity in gCO₂/kWh (location-based; marginal exists but is
an accounting-policy choice and is not modeled). The two models are **not** interchangeable:
a low-intensity grid and a renewable-budgeted platform can disagree about which schedule
is "greener" (e.g., a huge always-on fleet under a generous budget is zero-carbon in CWM
terms yet large in CASPER grams). The platform implements **both, explicitly named**,
because conflating them would be a modeling error.

## 2. Architecture

### 2.1 The core decision: two named carbon models, both auditable

Following §1's finding, the platform declares two carbon accounting contracts. Both ride
on the energy dimension's per-VM busy/idle windows — **no new physics, only new accounting
on already-conserved quantities**:

**Intensity model (`CARBON_INTENSITY_V1`)** — multiplier accounting:
```
VmSpec:  + double carbonIntensityGramsPerKWh        // declared per VM (region proxy)
Metric:    totalCarbonGrams = Σ_vm E_vm[J] / 3.6e6 × intensity_vm[g/kWh]
           perVmCarbonGrams = {vm: E_vm[J] / 3.6e6 × intensity_vm[g/kWh]}
           meanVmCarbonGrams, carbonMakespanProduct (g·s)
```
(E in joules; kWh = E/3.6e6, so grams = E[J]/3.6e6 × I[g/kWh].)
This is the **CASPER-family accounting**; heterogeneous per-VM intensity models
geo-distributed fleets. Energy either must be powered (E>0 interval means carbon>0 means
powered) or intensity-with-unpowered-E is silently zero — rejected (below).

**Budget model (`CARBON_BUDGET_V1`)** — excess accounting:
```
PlatformProfile:  + List<CarbonWindow> carbonWindows      // [startSeconds, endSeconds, greenWatts]
Metric:    totalCarbonExcessWattSeconds = ∫₀ᵀ max(0, P_fleet(t) − green(t)) dt
           perVM shares = vm's contribution at over-budget instants
```
The **P_fleet(t) curve is exactly derivable from per-VM busy windows + the linear power
model** (P_idle constant + P_work active windows) — the data the auditor already
recomputes. This is the **CWM-family accounting**; window arrays are the researcher's
declared renewable-availability scenario (like CaWoSched's `G_j` intervals). Windows must
be sorted and non-overlapping. They define the accounting horizon rather than a promise to
cover the entire simulation: outside the declared windows is not included in the excess
integral; time after the last window contributes no excess.

The report has one deliberately label-specific compatibility rule. For `CAWOSCHED_SLACK` and
`CAWOSCHED_PRESSURE` on flat-power fleets, the budget integral uses the full configured deadline
horizon and each active Job's own VM dynamic delta `P_max(vm) − P_idle(vm)`, matching the timed
planner's deadline and heterogeneous-processor inputs. Other planning labels retain the historical
report convention: the simulation-end/makespan horizon and the first VM's dynamic delta for every
active VM. The tiered per-tier budget branch is unchanged. Consequently, a CaWoSched plan and its
report use the same horizon and power law without changing historical non-CaWoSched evidence.

### 2.2 Manifest and audit

- `energyMetrics` unchanged; new sibling `carbonMetrics` section emitted when either
  carbon model is declared (else absent — legal)
- Auditor recompute: intensity model = multiply audited E (already component-verified)
  by declared intensities, compare; budget model = rebuild the fleet-power timeline from
  the same busy intervals + power fields, integrate excess per window, compare. **Both
  carbon metrics therefore rest on quantities that already passed the energy
  conservation law** — carbon can't silently drift while energy is locked
- Same conditional-audit pattern (declared → required; undeclared → skipped; legacy
  manifests unaffected)

### 2.3 Workbench JSON surface
```
"platform": { "carbonIntensityGramsPerKWh": 380.0 }        // uniform, intensity model
"platform": { "carbonWindows": [ {"startSeconds":0,"endSeconds":3600,"greenWatts":500}, ... ] }
```
Validation: both together rejected (one model per run — the contract is the analysis);
budget windows well-formed; intensity ≥ 0 finite.

### 2.4 Power-model requirement

- **Intensity model requires a powered profile**: carbon multiplies *energy*; an
  unpowered profile makes it vacuous. Fail-fast with the power-model message.
- **Budget model also requires powered**: the fleet-power curve needs P_idle/P_work.
- Algorithm declarations: `carbonAware`/`carbonModelRequired`/`carbonObjectiveWeight`
  fields mirroring the energy declarations with the same implication lattice.

### 2.5 Carbon-aware scheduling

The registered `CARBON_DEFER_FIRST` demonstrator exercises the carbon-aware registry path. The
core scheduler label `CARBON_WINDOW_DEFER` reads the declared `carbonWindows`, finds the next
window whose green power is *strictly* higher than the current one, and holds the entire ready
batch until that window starts, each Job at most once. The bound is **per Job, not per
workflow**: a Job that becomes ready after an earlier wave was deferred may hop once itself, so a
dependency chain can shift once per wave until no cleaner window remains. The scheduler arms a
wake-up event for the window start; a deferral-aware policy that leaves ready Jobs unscheduled
with no future wake time fails explicitly.

The rule is driven by the declared window table, not by a load forecast, and trades completion
time for lower excess. A platform without `carbonWindows` is rejected rather than degraded to
plain FCFS. Deferral cannot go below the **idle floor**: a fleet whose parked power exceeds a
window's green budget pays `Σ P_idle − green` for the whole window regardless of what runs
(`deferralCannotGoBelowTheIdleFloorOfADirtyWindow`: two 100 W idle VMs against a 120 W window
leave 80 W × 100 s). In `CarbonWindowDeferSchedulingIntegrationTest`, with a dirty `[0,100)`
window (120 W green) and a clean `[100,400)` window (400 W green) on a 300 W active platform,
FCFS incurs positive excess while the deferral skips the dirty window, leaving zero excess at a
longer makespan; without a window table the label is rejected.

## 3. Deliberate omissions

| Omitted | Reason |
|---|---|
| Grid datasets / forecasts (Electricity Maps feeds) | External-data dependency (unauditable provenance); researchers declare windows/intensities — same candor as CWM's synthetic power profiles |
| Marginal vs location-based accounting | Accounting-policy dispute; the platform is location-based only, stated in the metric contract |
| Spatial multi-region execution | Our platform is single-fleet; per-VM intensity captures heterogeneity, not geo-routing (CASPER systems work, different layer) |
| CWM-class DP mapping algorithm | The platform provides the accounting, registry hooks, and the deferral label; the NP-hard joint mapping is out of scope |

## 4. Competitive positioning

No surveyed simulator implements carbon accounting with an independent recompute chain.
CWM/CaWoSched evaluate algorithms on their own accounting; CASPER measures with live
data — neither publishes a conservation-law-style invariant a third party can verify from
the artifacts alone. Every gram and every excess watt-second here is recomputable from the manifest by the Python auditor.
