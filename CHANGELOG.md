# Changelog

## 1.0.0

First public release of WfLedger.

### Algorithms

- Online Min-Min, Max-Min and MCT rank completion times with machine ready times, following
  Braun et al. (2001) and Maheswaran et al. (1999), instead of considering idle machines only.
- IC-PCP selects the critical parent by latest data arrival and checks each path task against its
  latest finish time.
- MOHEFT keeps the best schedules across fronts with crowding distance; GreenHEFT and MOHEFT use
  the same shared-storage execution model as HEFT.
- DLS uses the median static level and the heterogeneity term of Sih and Lee (1993).
- EBPSM distributes a per-workflow budget and updates it after every task, as in Hilman et al.
  (2019).
- CRCH charges checkpoint overhead on every attempt and resumes from a checkpoint only on the
  machine that took it.
- The legacy clustering family, the reinforcement-learning demo and the deprecated scheduler
  aliases are removed.

### Simulation model

- A failure ends the running attempt at the failure time instead of at its completion.
- The local energy-delay heuristic no longer depends on the dispatch time.
- Fleets whose default frequency tier and nominal speed disagree are rejected.
- The shared-storage rate accepts fractional values, so communication-to-computation ratios can be
  set exactly.

### Metrics

- The bounded-slowdown threshold is configurable and the share of clamped samples is reported.
- Input-read time inside busy time is reported separately from compute time.
- Bandwidth cost counts each real input transfer once.

### Experiments

- A standard study design: heterogeneous fleets of 8 to 32 VMs with linear prices, a calibrated
  communication-to-computation ratio, Weibull failures, interpolated deadlines, and paired
  Wilcoxon tests with Holm correction and effect sizes across workflow instances.
- A reproducible synthetic corpus of 360 WfCommons instances across eight applications.
- Per-run evidence bundles are optional and off by default.
- Run records identify the code by its git commit.
