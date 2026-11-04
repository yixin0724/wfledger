# Audit Coverage Ledger

This page answers one question: **what has actually been verified, by which command, and what is
deliberately out of scope.** It is a ledger of coverage, not a claim that no defect exists. Every
row names the test or command that fails when the property breaks.

Last sweep: 2026-11-03 (review rounds 1-7, the dedicated coverage goal, the AUD-F05 validator
recompute, the AUD-F13 dataset index chain, and the AUD-F08 shared-core honesty pass); updated
2026-11-04 by the post-completion review follow-ups (three AUD-F12 javadoc path residuals in
5922281, the finding-disposition master ledger added as §7, the vendored-tree diff registry
note in 4f94e0d, the AUD-F07 cap configuration outlet in 4becd96 with the M20 canary, and the
AUD-F23 declared revision contract with the M21 canary).

## 1. Evidence dimensions: a positive case and a tamper case each

| Dimension | Positive (must pass) | Negative (tampering must be refused) |
|---|---|---|
| Energy conservation | `CarbonAccountingTest.intensityUnitLawHoldsOnARealRun`; independent recompute in `verify-study-evidence.py` | `CarbonAccountingTest` corruption cases; auditor self-test `energy-bad` bundle |
| Carbon (intensity) | `CarbonAccountingTest.tieredPlatformIntensityRidesTheConservedPerTierEnergy` | `carbon.totalGrams` / `perVmCarbonGrams` / intensity tampering killed by the auditor (fuzz sweep, §4) |
| Carbon (budget windows) | `CarbonAccountingTest.tieredPlatformBudgetCarbonUsesThePerTierFleetPowerCurve`, `mixedFleetBudgetCarbonMixesTieredAndFlatContributions` | `window.greenWatts` / `totalCarbonExcessWattSeconds` tampering killed (fuzz sweep) |
| DVFS per-tier ledger | `network`-independent: auditor per-tier recompute; `CarbonWindowDefer...` not applicable | `tier[0].mips`, `tier[0].maxPowerWatts`, `job.tierMips` tampering killed (fuzz sweep); `tier-speed-stale` / `tier-undeclared` self-test bundles |
| Network ledger | CI `verify-network-ledger.py` + producer fixtures | `test-network-ledger.py` negative fixtures |
| File lifecycle V2 | CI `verify` fixtures + `_file_lifecycle_audit` | `test-file-lifecycle.py` negative cases |
| Storage lifecycle V3 | CI fixtures + `_storage_lifecycle_audit` | `test-storage-lifecycle.py` negative cases |
| Event stream | `EventStream` ordering/sequence/count checks + the declared `events.revisionContract` | out-of-order time, duplicate sequence, blank line, eventCount, first/lastSequence, unknown contract — refused; with the contract declared, a stream that removes `SIMULATION_TERMINATED` or every marker is refused instead of being read as a legacy stream (Python self-test + Java validator test, AUD-F23) |
| Simulation metrics v4 | Java validator recompute (AUD-F05) + `verify-study-evidence.py` derived metrics | consistently-tampered `makespanSeconds` / `meanJobVmQueueWaitingTimeSeconds`, shifted terminal clock, broken start derivation, uncovered executed job — all refused (Java validator test; Python auditor) |
| Dataset integrity indexes | `check-dataset-indexes.py` passes over the standard checkout in CI and over the full local snapshots | 19-case self-test: digest tampering, missing retained file, broken index chain, unindexed disk/Git file, count drift — all refused (AUD-F13) |

## 2. Rerun path: which tampering yields which verdict

Measured against a real bundle with `RerunDiffExecutor` (see the fuzz harness note in §4).

| Tampering | Verdict | Exit |
|---|---|---|
| `configuration.vmCount`, `schema`, artifact hash/size, missing artifact, `workflowProfile.taskCount` | `EVIDENCE_INVALID` | 5 |
| consistently-tampered `metrics.makespanSeconds` (manifest + sidecar in step) | `EVIDENCE_INVALID` since 2026-11-03 (AUD-F05 validator recompute; previously reached the diff and reported `DIVERGED`) | 5 |
| input `sha256` | `INPUT_HASH_MISMATCH` | 3 |
| `platform.vms[].mips`, `energyMetrics`, scheduling label, or `rootSeed` on a stochastic workload | `DIVERGED` | 1 |
| malformed tier table, unknown/non-string `frequencyPolicy`, windowless deferral label | `RECONSTRUCTION_REJECTED` | 4 |
| unchanged evidence | `IDENTICAL_CORE` | 0 |

The seed row is proven both ways by `SeedSensitivityRerunIntegrationTest`: a stochastic
failure/retry workload reruns `IDENTICAL_CORE`, and bumping the recorded seed diverges on the
result face. (A tampered seed on a *deterministic* workload cannot diverge — the rerun faithfully
reproduces the recorded conditions, so both sides carry the same seed; that is consistency, not a
miss.)

## 3. Mutation testing: do the tests actually detect breakage?

| Mutant | Outcome |
|---|---|
| Repository gate (`make mutation-gate`, 27 registered mutants after the V&V B3 work; M12–M19 added 2026-11-03 for the audit fix paths, M20 for the cap outlet, M21 for the declared revision contract, M22/M23 for B1, M24–M26 for fixed-commit references, M27 for Montage runtime mapping) | M1–M11 11/11 KILLED (full battery); M12–M19 8/8 KILLED (red-first, per-mutant gate runs); M20 1/1 KILLED (20/20 aggregate); M21 1/1 KILLED (21/21 aggregate, 0 survived, 0 stale, 437 s); M22–M27 are verified red-first at the killers stage in the B1–B3 close-outs |
| B1: `mutable()` stops copying (copy-on-write disabled) | KILLED by `RuntimeHistoryIsolationTest` |
| A2: budget always takes the v1 path | KILLED by `CarbonAccountingTest` |
| A1: `frequencyTiers` rebuilt as empty | KILLED by six rerun tests |
| C1: window comparison flipped `>` → `>=` | KILLED by the deferral suite |
| C1: per-Job deferral bound removed | KILLED after the ladder test was strengthened (round 5) |
| Auditor: intensity unit conversion `3.6e6` → `3.6e5` | KILLED by the auditor self-test |
| Auditor: event-sequence-bound check disabled | KILLED by the new self-test case |
| Auditor: per-tier busy-sum assertion disabled | **survives** — redundancy, not a hole: any tampering that could break the equality (same-VM overlap across tiers) is already killed by `vm-overlap` and the attribution checks |

## 4. Cross-language fuzz sweep

Harness: take a real bundle (intensity model and tiered+budget model), mutate one field at a time,
copy the whole run directory, and require `verify-study-evidence.py` to reject. The harness had to
copy the events sidecar; a manifest-only copy makes every case "fail" vacuously.

- 23 mutations per model covering energy, metrics, job intervals/identities, carbon grams and
  excess, window table, tier table and tier identity, intensity, event count and sequence bounds.
- Result: **zero survivors** on fields that feed a recomputed quantity.
- Two fields are declaration-only and cannot be cross-checked from the bundle: a stage-in
  envelope's `taskIds` (empty by construction) and `platform.vms[].pes` (enters no audited formula
  for single-PE workloads). See the reproducibility contract's "Declaration-only fields" note.

### Sidecar sweep (2026-11-02)

The three evidence sidecars were fuzzed the same way, through their own auditors
(`_network_audit.inspect_path` dispatches V2/V3 as well): mutate one field, copy the whole run
directory, require a rejection.

| Sidecar | Mutations | Result |
|---|---|---|
| Network ledger | admitted payload, serviced balance, modeled rate area, trace-snapshot event drop, engine time, empty bindings, schema, accounting version | all rejected (`CheckError`) |
| File lifecycle V2 | retained-record count, event drop, certificate scope, observation watermark | all rejected |
| Storage lifecycle V3 | retained-record count, event drop, certificate scope, missing `sourceStorage` | all rejected |
| Manifest/events (network bundle) | event drop, `networkEvidence.mode` flip, ledger artifact hash | all rejected — the hash case by the main auditor (`artifact-sha256`) and by the network auditor |

Note for anyone repeating this: the artifact role string in this manifest is `network-ledger`
(camel-case `networkLedger` matches nothing, so the "tamper" is a no-op and every auditor rightly
passes). Two other false positives in this sweep had the same shape — a mutation that changed
nothing (`events.count` instead of `eventCount`, a stage-in envelope's already-empty `taskIds`).

## 5. Deliberate boundaries (not defects)

- Budget windows are excluded from the v1 Workbench surface, so `CARBON_WINDOW_DEFER` is rejected at
  Workbench validation with a pointer to the `SimulationRunner` API.
- The frozen `benchmark-standard-dvfs-v1` tar predates the tier rebuild (A1); it remains a valid
  record, and freshly produced tiered evidence now re-runs.
- The deferral engine's bound is per Job, not per workflow: a later ready wave may hop once more,
  until no cleaner declared window remains.
- The four Python contract auditors (V1 network, V2 file lifecycle, V3 storage, online
  assignment) share one implementation core, `_network_audit.py`, by design: splitting them
  would create four duplicated implementations. Their claimed independence is independence
  from the Java production path, not independence from each other (AUD-F08).
- Five bidirectional Java package pairs remain by historical growth (data vs network,
  data.v2 vs utils, experiment.registry vs scheduling, experiment.registry vs utils,
  failure vs utils); `PackageDependencyGuardTest` locks this inventory and the hotspot
  outgoing-edge allowlists so the cycles cannot silently grow (AUD-F16). Untangling them
  fully requires relocating aggregate carriers such as `SimulationConfig` (which
  necessarily references the per-domain contract objects) — the recorded decoupling
  proposal is to move `SchedulingAlgorithmProvider` into a leaf package, which breaks the
  `experiment.registry <-> scheduling` pair and halves `experiment.registry <-> utils`.
- The `output/` tree follows a retention policy: uncompressed study and workbench directories
  are regenerable working products, removable with `make clean-output`, while every retained
  evidence original lives as an `output/*.tar.gz` archive. The large raw directories were
  removed on 2026-11-03 after verifying, for each archived directory, that the archive holds
  every raw file (AUD-F18).
- The vendored `org.cloudbus.*` tree is maintained by this repository: repository-wide commits
  normalized its formatting (`cc08dd1`, `7ad2a69`), translated its javadoc to English
  (`3950109`), and removed 68 vendored dead files (`f27ba8a`). The audit-fix campaign's only
  semantic API addition to the vendored tree is `Log.printError(String, Throwable)`
  (AUD-F19, `1f316c9`), annotated in a header comment at the top of `Log.java` so a future
  re-vendoring cannot silently drop it.

## 6. Commands that constitute the gate

```bash
mvn -Pjavadoc clean verify                                   # unit + integration + coverage + javadoc
python3 -B scripts/check-docs.py                             # link/anchor validation
python3 -B scripts/check-language.py                         # English-only language gate
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py   # 38 auditor self-tests
for t in scripts/test-*.py; do PYTHONDONTWRITEBYTECODE=1 python3 -B "$t"; done
python3 -B scripts/check-dataset-indexes.py                   # dataset index integrity chain (AUD-F13)
node scripts/verify-report.cjs <fixtures>/browser-fixtures.json     # offline report acceptance
python3 -B scripts/mutation-gate.py --output <new>.json       # registered mutants
```

## 7. Audit-fix campaign: finding-to-disposition master ledger

The 2026-11-03 audit produced one CI claim and twenty further P0/P1/P2 findings across the
simulator, the experiments module, the evidence pipeline, and the process. The authoritative
per-finding rows (level, evidence location, fix plan, execution notes, and the standing user
decisions) live in the engineering plan document maintained in the engineering workspace
outside this repository; this section is the in-repo disposition summary, so any checkout can
answer "what was each audit finding and what happened to it" on its own.

| Finding | Level | Subject | Disposition |
|---|---|---|---|
| F-01 | P0 | CI referenced an untracked language-gate script | **False alarm** — `scripts/check-language.py` and its self-test were committed in `4ee6219` (2026-11-03 13:51 +0800) before the audit conclusion; the audit's untracked-marker evidence was a stale snapshot taken before that commit. No fix was needed. |
| F-02 | P0 | mutation gate never runs in CI; only 11 registered mutants | FIXED — `12630c7` (CI runs the full zero-survival battery on push and PR); `6fd98e5` + `fa0cb1e` (eight new canaries M12–M19, one per audit-fix semantic axis, red-first 8/8 KILLED; the logging channel of AUD-F19 is argued unregistered) |
| F-03 | P0 | coverage floors low; BUNDLE aggregation; integration coverage uncounted | FIXED — `6eaddfc` (bundle→PACKAGE ratchets, integration-exec rules added, thresholds set by measured ratchet) |
| F-04 | P0 | contention engine `advance()` O(B²): full-list fork each time step | FIXED — `a056426` (shared immutable retained-trace prefix across forks; byte-identical trace proven) |
| F-05 | P1 | artifact validator checked metrics existence but never recomputed | FIXED — `fd3a723` (queue-wait recomputed from `result.jobs` with the same population and formula as the Python auditor; event contract boundaries enforced); boundary closed by F-23 |
| F-06 | P1 | Holm correction only on the network study path | FIXED — `65254bc` (shared corrector in the simulator module; applied within each comparison group) |
| F-07 | P1 | unbounded resident event list in `SimulationEventRecorder` | FIXED — `a4ca13c` (fail-fast capacity cap, default 20000); follow-up `4becd96` (2026-11-04): the cap gained a full configuration outlet — SimulationConfig builder `eventRecorderCap`, runner wiring, workbench `simulation.eventRecorderCap`, manifest round-trip; M20 canary registered, 20/20 KILLED |
| F-08 | P1 | four "independent" Python checkers share one 49KB core | FIXED — `167b7e0` (honest shared-implementation declaration; independence means independence from the Java production path) |
| F-09 | P1 | dual cost calibers under one algorithm label | FIXED — `ae6231f` (mixed mapping-cost calibers rejected within one comparison group) |
| F-10 | P1 | deprecated label bypass behind the scheduler | FIXED — `f20925a` (explicit legacy declaration required; no silent alias mapping) |
| F-11 | P1 | RL interface non-Markov, silent illegal actions, terminal-only reward, static registry | FIXED — `7e3ca25` (demo-boundary declaration per user decision; behavior unchanged) |
| F-12 | P1 | fat-tree routing triple implementation (Java topology / Java validator / Python audit) | FIXED — `10e5492` (single Java route source for topology and validator; Python kept as the cross-language evidence path); residual follow-up `5922281` fixed three stale javadoc path pointers found by the 2026-11-04 review |
| F-13 | P1 | dataset tree without a top-level sha256 index chain | FIXED — `6871dca` (top-level `datasets/INDEX.json`, wfinstances index, sha256 chain verified by `check-dataset-indexes.py`) |
| F-14 | P1 | 2090-line `SimulationMetrics`; silent miss on accumulator/assembler/getter triple edits | FIXED — `a1de55e` (serialization-surface contract guard; class split rejected as "move-only" risk) |
| F-15 | P1 | stateful context validator, not reentrant | FIXED — `bf93926` (single-use lifecycle latch; feeding rejected after finish) |
| F-16 | P1 | 14 package cycles | FIXED — `9490617` (cycle inventory locked by `PackageDependencyGuardTest`; full untangle recorded as a proposal) |
| F-17 | P2 | Workbench UUID/timestamp output dirs; full-rewrite write amplification | CLOSED-BY-ARGUMENT — deliberate design: per-run dirs avoid parallel collisions, identity/environment/wall-clock are whitelisted by the rerun contract, per-candidate rewrite is crash tolerance |
| F-18 | P2 | `output/` at 3.6GB with no executed retention policy | FIXED — `a50054f` (pre-deletion manifest recorded, non-retained uncompressed dirs removed, all `output/*.tar.gz` originals kept and coverage-verified, `make clean-output` added); the per-archive figures are reproduced in §8 so the retention boundary is auditable from this checkout alone |
| F-19 | P2 | legacy catch sites swallow exceptions via `printStackTrace` | FIXED — `1f316c9` (vendored `Log.printError` channel; control flow including the historical null continuation unchanged) |
| F-20 | P2 | Tier-B backlog plus permanent exemption zones; ratchet broken | CLOSED-BY-RECORD — exemption structure frozen by AGENTS.md policy; this campaign's new code held to zero Tier-A |
| F-21 | P2 | `default` fallback to Static hides a missed registration edit | FIXED — `0d33ca0` (every scheduling label gets an explicit binding; INVALID binds the no-decision shell) |
| F-22 | — | (audit self-correction) `verify-network-ledger.py` suspected dead code | CLOSED-BY-VERIFICATION — referenced by five documents and three self-test scripts (`test-network-ledger.py`, `test-file-lifecycle-context.py`, `test-dataflow-assignment.py`); the audit conclusion was withdrawn |
| F-23 | — | (2026-11-04 post-completion review) a v4 stream whose markers were removed was indistinguishable from a legacy stream, so the audit passed a bundle with every `JOB_EXECUTION_STARTED` and the `SIMULATION_TERMINATED` deleted and the manifest re-signed | FIXED — a writer that emits the markers now declares `events.revisionContract` (`F-006/F-007`); both validators require exactly one terminal event and full execution coverage when it is declared, and keep the declared manifest-authority boundary when it is absent (7,402 of the 7,984 retained v4 bundles predate the declaration, so a blanket requirement would have rejected most historical evidence); M21 canary kills the "ignore the declaration" regression. Residual boundary: a bundle whose entire stream was removed looks like a run with recording disabled, which is a manifest-authority case by design |

## 8. Retained-evidence archive coverage (AUD-F18 auditability follow-up)

The 2026-11-03 cleanup (`a50054f`) deleted the regenerable uncompressed study/workbench
directories after checking that each archive holds the raw files, and that check was recorded
in the deletion commit rather than in this repository. The table below is measured from the
archives themselves at the current checkout, so a reader can confirm the survivors, their
sizes, and their run counts without the deleted directories. Where the deletion commit stated
a file count, the measured count is given beside it for comparison; a post-deletion
re-computation of "the archive holds every raw file" is no longer possible, and the per-run
manifests inside the archives remain the record of what each run contained.

| Archive | MiB | v4 run manifests | files | deletion-commit figure |
| --- | ---: | ---: | ---: | --- |
| `benchmark-standard-v1-evidence.tar.gz` | 21 | 160 | 482 | 482/482 match |
| `benchmark-standard-power-v1-evidence.tar.gz` | 21 | 160 | 482 | 482/482 match |
| `benchmark-standard-dvfs-v1-evidence.tar.gz` | 12 | 90 | 272 | 272/272 match |
| `benchmark-standard-carbon-v1-evidence.tar.gz` | 22 | 170 | 512 | full raw structure (not itemized) |
| `sensitivity-r13-v2-evidence.tar.gz` | 47 | 546 | 1645 | 1645 >= 1643 match |
| `sensitivity-r13-evidence.tar.gz` | 46 | 546 | 1643 | not stated |
| `network-study-r10-evidence.tar.gz` | 42 | 504 | 1517 | not stated |
| `network-study-r10-pilot.tar.gz` | 54 | 564 | 1697 | not stated |
| `network-v2-858f46a.tar.gz` | 136 | 1647 | 39569 | not stated |
| `config-audit-1b6baa8.tar.gz` | 206 | 2382 | 7292 | not stated |
| `scalability-audit-c62c6b8.tar.gz` | 105 | 1195 | 3679 | not stated |
| `peft-comparison-r12-evidence.tar.gz` | 10 | 126 | 383 | not stated |
| `carbon-c2-audit-evidence.tar.gz` | 0.3 | 2 | 10 | not stated |

All thirteen archives open and list cleanly, and every retained score file is one of the
`output/*.tar.gz` originals that `make clean-output` cannot match (the target uses
trailing-slash directory globs). `datasets/` is excluded from this table: it is source input,
not run evidence, and its integrity chain is §1's dataset row.

## 9. V&V engineering follow-ups (2026-11-04)

The following items were found during the V&V design phase after Review-R1. They are new
follow-ups, not reopened F-01..F-23 findings. Each disposition links to the guard that prevents
recurrence; no historical ledger rows were removed.

| Item | Subject | Disposition |
|---|---|---|
| Q03 | Carbon intensity formula in the design document used `E/1000 × intensity`, while Java and Python use `E/3.6e6 × intensity` | FIXED — formula corrected to the implemented unit law; `DocumentationConsistencyTest` locks it |
| Q04 | Carbon-window text required full `[0,T_sim)` coverage, while implementation and retained evidence integrate only the declared horizon | FIXED — contract now states sorted/non-overlapping declared windows and excludes time outside them; no behavior change |
| Q06 | Benchmark verification commands referenced removed uncompressed evidence directories | FIXED — guide now restores tar originals into a new empty directory and audits there; current standard/power/carbon/DVFS bundles re-audited 160/160/170/90 with zero issues |
| Q08 | DVFS design notes simultaneously said tiered evidence was rerunnable and non-rerunnable | FIXED — historical D4 boundary explicitly marked superseded by A1; current round-trip and `IDENTICAL_CORE` contract stated |
| Q14 | Model count, Energy Portrait title, and mutation-duration guidance drifted | FIXED — nine physical models, corrected title, and a consistent 5–10 minute estimate; `DocumentationConsistencyTest` guards the current text |
| Q02 | Generic campaign summary was serialized without an independent recompute or tamper check | FIXED — `ExperimentCampaignSummaryVerifier` rebuilds cell, completion, interval, paired, and Holm aggregates from validated manifests; deterministic and independent-replication tamper tests plus M22 guard it |
| Q05 | WfFormat parser retained the declared version but could silently ignore unknown versions and fields | FIXED — 1.5/1.6 required-field, type, range, and unknown-field validation runs before Gson binding; unknown versions fail explicitly and real-input cross-validation stays green |
| Q07 | No systematic fixed-commit upstream differential layer existed for CloudSim/WorkflowSim semantics | FIXED for the declared B02/B03 intersection — CloudSim serial/send and Space-Shared references plus the legacy SHARED 150 MB/15 MB/s case and M24–M26 now anchor it; old labels, full examples, and failure semantics remain deliberately excluded |
| Q13 | Statistical validity and application scenario coverage needed stronger independent inputs | PARTIAL (B3) — all 20 classic DAX and all 17 Montage WfInstances now have independent structural, unit, DAG, and missing-field checks; no real-platform validation is claimed |
