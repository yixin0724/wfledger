# Reproducible Runs and Evidence Recording

An interpretable run is defined jointly by **its inputs and their order, the platform, the complete configuration, the random seed, and the actual implementation identity**. Reproducibility does not mean real-platform calibration, WfInstances trace replay, or universal algorithmic superiority. Day-to-day operation is covered in [Workbench](<../getting-started/WORKBENCH.md>); this document states what must be recorded, and what recording cannot prove.


## Configuration and Defaults

Research code enters the standard runner through the immutable [SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>) and `PlatformProfile`. Do not rely on static parameters left behind by the previous run.

| Condition | Current conventions |
| --- | --- |
| Root seed | The Java configuration default is 0; the Workbench default seed list is `[42]`. Studies must explicitly record every actual seed |
| Algorithm and file system | Java defaults to FCFS, INVALID planning, and SHARED; other combinations must be selected explicitly and pass validation |
| Data movement | Defaults to `LEGACY_WORKFLOWSIM_V1`; switching the model must not be treated as an implementation detail that leaves results unchanged |
| Runtime conversion | Defaults to `runtimeReferenceMips=1000.0` and `runtimeScale=1.0`; seconds convert to MI as `max(100, floor(runtime * referenceMips * scale))` |
| Kernel tick | `cloudSimMinEventIntervalSeconds` is a positive finite value, defaults to 0.1 simulated seconds, and affects event release and completion; the completion safety margin is `tick/10` and the initial-processing threshold `tick + tick/10 + tick/100` (0.01 s and 0.111 s at the default); it is not a network-latency calibration |
| Arrival times | The inputs and the equally long arrival table are known before startup and released at seconds counted from simulated time zero; all zeros by default |
| Deadline | 0 means not requested; a positive signed-long threshold only observes `simulationEndSeconds` and does not change scheduling, admission, retries, or termination |
| Overheads and failures | Off by default; when enabled, record the immutable distribution specifications, depth/VM layout, and retry budget — never reuse an already-consumed random generator |
| Execution domain | The standard runner requires NONE clustering, SPACE_SHARED, and PE-compatible placement, dispatching at most one Job per VM at a time |

Deadline comparison keeps the signed-long threshold and the exact binary64 value of the end time: compare first, then convert slack/tardiness into display values, without epsilon; not-requested, timed-out, and unfinished workflows must each be interpreted on their own. It is not an implicit deadline on logical task completion times.

Input formats, normalization, and source scope are covered in the [datasets note](<../../datasets/README.md>). DAX permits no DOCTYPE/external entities; an external DTD outside the recorded input cannot change the model. Preserve the original input; retaining only the file name or the parsed graph is not enough.

## Seeds, Sessions, and Retries

[SimulationRandom](<../../simulator/src/main/java/org/workflowsim/utils/SimulationRandom.java>) derives separated deterministic random streams keyed by stable component names; [SimulationSession](<../../simulator/src/main/java/org/workflowsim/utils/SimulationSession.java>) installs the seeds and models before a serial run and cleans up the global state it owns at the end. Concurrent sessions in the same JVM are rejected. The mutable-static ledger audited by `SessionResetMatrixTest.AUDIT_CLASSES` covers 68 non-final static fields in 11 classes.

With identical code, inputs, platform, and configuration, repeated runs should rebuild the same simulated random process; the sampling order inside a component can still vary with scheduling, release batches, or retries. **A shared root seed is not event-keyed common random numbers (CRN)**, nor does it by itself constitute independent replicates or a legitimate inference of treatment effects. Design labels, actual seeds, and the statistical interpretation of batch runs are covered in the [campaign guide](<CAMPAIGNS.md>).

Overhead distributions are keyed by depth; the formation of batches delayed by the workflow engine depends on the actual release events. Keep the configuration together with the actual samples recorded in the events, such as queueing/post-processing draws; do not mistake same-named random streams under different algorithms for a one-to-one per-event correspondence. Custom extensions that use random sources outside `SimulationRandom` must declare their seeds, state, and reproduction tests separately.

Standard failure recovery uses `FTCLUSTERING_NOOP` and `MONITOR_NONE`; enabling failures requires a positive total retry-Job budget and a valid distribution layout. Budget exhaustion fails explicitly and is not the same thing as a workflow-failure sample that ended normally. Every retry gets a new Job identity, and the failed parent attempt is retained; failed outputs cannot become a readable copy for successors. Examples and layout constraints are in the [Java API guide](<../getting-started/CODE_CONFIG_EXPERIMENTS.md>).

## Workload, Platform, and Timing Conventions

[TaskExecutionModel](<../../simulator/src/main/java/org/workflowsim/utils/TaskExecutionModel.java>) converts matrix seconds into positive-integer single-PE MI via `Math.round(seconds * vmMips)`. When a matrix exists, no fallback on missing coordinates is allowed; MI×PE×1,000,000 must be representable as a signed long. `TaskOutcome.lengthMi` keeps the original normalized length, while `effectiveExecutionLengthMi` is the actual computational workload of the current attempt, excluding stage-in.

The Job envelope and the Task computation window must not be conflated: legacy/fixed-endpoint models can count effective input latency into the Job envelope, while pre-execution/coherent models prepare the inputs before the CPU starts. Task timing uses the effective workload; whether it coincides exactly with the whole Job window is stated by the corresponding fields. A failure ends its attempt at the first failure arrival inside the modeled compute window (see the [algorithm contracts](<../algorithms/CONTRACTS.md>)); it is not a calibrated crash or partial-write model.

`simulationEndSeconds` and `makespanSeconds` are the same simulation end clock. `logicalTaskCompletionSeconds` is available only when all logical Tasks succeed, taking the maximum over Tasks of each Task's first successful Job-envelope completion time; the difference `terminalLifecycleTailSeconds` may include tails such as output write-back and is not pure CPU or scheduler time.

**Event-stream recomputation.** events v1 contains two marker types. `SIMULATION_TERMINATED` is recorded exactly once on natural completion, after the watchdog and quiescence checks, with event time and `simulationEndSeconds` equal to the termination clock; `makespanSeconds`, `terminalLifecycleTailSeconds`, and the deadline-slack family can therefore be recomputed from the stream. Aborted runs omit it, and its absence marks an incomplete execution. `JOB_EXECUTION_STARTED` is recorded once per Job execution instance before its first `TASK_EXECUTION_MODELED`, carrying `jobEnvelopeStartSeconds`, `effectiveDataStageSeconds`, `requestedDataStageSeconds`, and `modeledJobStartSeconds` (envelope start plus effective stage seconds). The independent auditors check exact equality of the termination clock with the manifest end value, per-instance uniqueness, derived arithmetic, and full coverage of execution instances.

A writer that emits both markers declares `revisionContract: "F-006/F-007"` in the manifest events summary. With the declaration, a stream missing `SIMULATION_TERMINATED` or every marker is refused. Without it, the manifest result and metrics tables are authoritative and the marker checks do not apply. A bundle whose entire event stream was removed is indistinguishable from a run with recording disabled; that case falls under manifest authority. `ExperimentArtifactValidator` and `scripts/verify-study-evidence.py` implement the same rule. The markers are core data for the [rerun comparison](<RERUN_DIFF_CONTRACT.md>).

[PlatformProfile](<../../simulator/src/main/java/org/workflowsim/platform/PlatformProfile.java>) records Host/VM specifications, optional pins, and the capacity-feasible pre-check mapping; placements after actual VM creation must also agree with the pre-check. Use one Host per VM when Host-placement factors must be excluded. Co-location is a resource-configuration condition and does not mean that Host CPU interference, migration, or real-infrastructure utilization has been modeled or calibrated.

`CostModel.DATACENTER` and `CostModel.VM` are abstract pricing scopes; the latter requires explicit per-VM pricing. Processing cost covers the CPU envelope cost of every returned attempt and the bandwidth cost of the real input files (once per transfer) in continuous decimal MB, failures and retries included; memory/storage prices are declared but not billed, and there are no provider-bill rounding or real network-traffic accounting semantics. The full metric definitions are in the [algorithm, execution, and metrics contract](<../algorithms/CONTRACTS.md>).

## Dataflow and Recording Modes Are Also Run Conditions

The current physical models consist of five V1 kinds, two coherent file V2 kinds, and two bounded-storage V3 kinds. V1 pre-execution, coherent V2/V3, and ordinary online scheduling differ in compatibility; the full combinations are in the [model capability matrix](<../advanced/DATAFLOW_CAPABILITY_MATRIX.md>).

- V2 requires LOCAL; V3 can use LOCAL/SHARED and must declare the bounded SOURCE's location and its read/write/NIC capacities.
- Coherent without assignment enabled uses RANDOM/STATIC; explicit online assignment uses INVALID/STATIC with a versioned policy, binding once control-ready and preparing the inputs afterwards; it must not be conflated with ordinary CPU online scheduling.
- V3 SHARED input waits for the SOURCE commit and cannot bypass it even if the target holds a copy in cache; every successful output, including unused sinks and zero-byte files, must fulfill its commit obligation. A CPU return is not storage completion.
- Capture budgets do not change the physical run, but they decide whether the evidence is sufficient. The V1 group ledger may explicitly retain a truncated prefix; V2/V3 lifecycle exports require complete capture, and online-action attestation likewise requires complete and matching lifecycles.
- OFF does not mean there is no network. The coherent core `dataflowPlan`, the V3 storage conditions, and the enabled assignment configuration are still part of the run identity and must not be ignored because an optional sidecar is turned off.

The raw Java OFF run, the complete lifecycle certificates, the online-action boundary checks, and the per-interval fluid service proofs are different layers; passing one of them must not be interpreted as passing them all. Field and output rules are in [Workbench](<../getting-started/WORKBENCH.md>).

## Evidence Bundles and Component Identity

[ExperimentArtifactWriter](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentArtifactWriter.java>) writes manifest v4, metrics v2, and events v1, with provenance at v3; when recording is enabled, matching `network-ledger`, `file-lifecycle`, or `storage-lifecycle` sidecars are written as well. A manifest alone is not a complete bundle. The event stream itself is bounded: [SimulationEventRecorder](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationEventRecorder.java>) fails fast when its retention cap is reached (default 20000, sized from the largest measured benchmark-standard-v1 stream of 9008 events) instead of keeping an unbounded resident list. The cap is run configuration: raise `eventRecorderCap` (the `SimulationConfig` builder option or the Workbench `simulation.eventRecorderCap` field; a non-default value is recorded as `configuration.eventRecorderCap`) or explicitly use the disabled recorder.

The [manifest writer](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentManifestWriter.java>) records input order and identities, the arrival table, matrices, algorithm tags and contracts, data movement, failures/overheads, platform topology/storage, actual placement, Job/Task results, and the observation scope.

### Execution identity

The manifest records `executionSemantics`. Current code writes `WORK_CONSERVING_TASK_EXECUTION_V3`. Evidence declaring `WORK_CONSERVING_TASK_EXECUTION_V2`, or lacking the field, is historical: current code never writes it, verifiers accept it only in explicit historical mode, and it is never back-filled or relabeled. V3 changes observable results relative to V2 under an unchanged configuration, notably in online ready-batch cost and late binding, same-instant round coalescing, tick-derived completion thresholds, mid-execution failure, CRCH checkpointing, EBPSM budgets, mixed-power rejection, and several planners; the per-algorithm `implementationRevision` tags in [AlgorithmCatalog](<../../simulator/src/main/java/org/workflowsim/experiment/AlgorithmCatalog.java>) identify each algorithm's current semantics. The network-study protocol identities are listed in the [campaign guide](<CAMPAIGNS.md#protocol-identities>).

The legacy SHARED storage rate `platform.storage.maxTransferRateMbPerSecond` is a finite positive binary64 in decimal MB/s. An integer literal such as `15` and `15.0` denote the same value and compare equal in a rerun.

### Provenance

[Provenance](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentProvenance.java>) records the Git commit of the source checkout that produced the run and the working directory; JVM and OS information is kept in the manifest's `runtime` object. Together with the recorded input identities and the complete configuration, the commit identifies the run. When the commit is unavailable, the missing state is recorded explicitly. A research handover must also retain build-dependency versions and any external policies or resource inputs. A study protocol is bound by its logical ID and its original bytes.

## Validation, Rerun, and Retention

- **Integrity validation**: checks schema, relative references, artifact digests and sizes, counts, and context; supported older manifests are read without filling in missing semantics. The declared event-sequence bounds (`events.firstSequence` / `lastSequence`) must match the JSONL. For manifest v4 the Java validator also recomputes the event-contract cross-checks, `makespanSeconds` (the result end clock), and `meanJobVmQueueWaitingTimeSeconds` (the mean of `max(0, startTime − submissionTime)` over COMPUTE Job outcomes) with the Python auditor's tolerance, so a wrong value written consistently into manifest and sidecar is rejected.
- **Declaration-only fields**: `platform.vms[].pes` (no audited formula uses it for single-PE workloads) and the `taskIds` of a stage-in envelope (empty by construction) have no independent counterpart in the bundle, so tampering with them cannot be detected. Fields that feed a recomputed quantity (energy, carbon, per-tier busy seconds, Job intervals, tier identity, window table, event count and bounds, and for v4 the makespan and queue-wait families) are checked on both sides; see [verification](<VERIFICATION.md#4-evidence-verification>).
- **Actual rerun**: rebuilds the configuration from the recorded conditions, re-executes under the current code, and compares; it is not restoring a historical process, reusing cached results, or substituting events for the simulation. Concrete support and the precise whitelist are in the [rerun difference contract](<RERUN_DIFF_CONTRACT.md>).
- **Identity differences**: JVM/OS, provenance, paths, and wall-clock nanoseconds may change and must be recorded. Simulation time, input identity, algorithm tags, execution semantics, and non-whitelisted fields do not gain tolerance or exemptions on that account.

All Java/Python/browser commands are collected in [build and verification](<../getting-started/BUILD.md>). Passing tests only show that the corresponding assertions hold; they do not show real-world calibration or algorithmic superiority on arbitrary inputs.

Retain the inputs, protocol original bytes, complete evidence bundles, the Git commit, and the validation commands; the original evidence must not be rewritten during validation or a rerun. New runs use a separate output location. Build-directory cleanup and research-evidence retention are different operations; outputs whose retention has not been decided, and archived raw data, must not be treated wholesale as deletable cache.
