# Mutation Gate (Registered-Mutant Survival)

The mutation gate is a **test-battery honesty check**, not a coverage tool: it verifies that
the maintained test battery still *kills* every registered semantic mutation. A green build
only carries meaning if its tests actually fail when semantics break; this gate proves that
sensitivity on the core decision surfaces, one registered canary per axis.

The registered set lives **in code** — `REGISTRY` in
[scripts/mutation-gate.py](<../../scripts/mutation-gate.py>) — and this page documents scope,
usage, and ledger policy. The concept and the original nine canaries were validated by the
2026-10 mutation pilot (engineering archive), whose two survived mutants exposed real test
blind spots (fluid-completion tolerance magnification; balanced-merge tail unlink) and were
closed with dedicated boundary/conservation tests at that time.

## Registered mutant ledger (scope)

| ID | Semantic break injected | Axis guarded |
|---|---|---|
| M1 | HEFT upward rank takes the first child instead of the maximum | planning rank computation |
| M2 | MCT drops the availability term | static independent-task cost model |
| M3 | Dependency gate inverted (only unsatisfied jobs pass) | engine dependency gating |
| M4 | Fat-tree aggregation index picks `halfK` instead of `availableAggregateCount` | topology routing determinism |
| M5 | Fluid completion tolerance magnified 1000x | numerical tolerance boundary |
| M6 | MI conversion `floor` → `round` at the parser boundary | input quantization semantics |
| M7 | Balanced merge drops the child-side tail unlink | clustering conservation law |
| M8 | Online assignment halves the compute estimate | coherent binding nominal score |
| M9 | Task execution window offset by 1e-6 s | timing-window exactness |
| M10 | SHARED DLS dynamic level flips the earliest-start subtraction into an addition | DLS decision surface |
| M11 | Registered declaration's ANY scope covers no file system | registry declaration semantics |
| M12 | Engine fork drops the shared retained-trace prefix | contention trace fork conservation (AUD-F04) |
| M13 | Metric recompute mismatch check ignores the declared metrics value | artifact validator recompute (AUD-F05) |
| M14 | Holm step-down drops the `(familySize - rank)` multiplier | within-family multiplicity correction (AUD-F06) |
| M15 | Event recorder cap admits one event beyond the budget | recorder retention fail-fast (AUD-F07) |
| M16 | Cost-caliber guard rejects homogeneous groups instead of mixed ones | comparison-group caliber homogeneity (AUD-F09) |
| M17 | Deprecated-label bypass only throws when legacy aliases are enabled | legacy label gate closure (AUD-F10) |
| M18 | Finished lifecycle validator still accepts event feeding | lifecycle single-use contract (AUD-F15) |
| M19 | INVALID label binds the FCFS policy instead of the static no-decision shell | unmapped label explicit binding (AUD-F21) |
| M20 | Runner ignores the configured event-recorder cap and constructs the default recorder | cap configuration outlet (AUD-F07 follow-up, 2026-11-04) |
| M21 | Declared revision contract ignored, so a marker-less stream falls back to the legacy boundary | declared `events.revisionContract` requirement (AUD-F23, 2026-11-04) |
| M22 | Campaign validator skips the independent statistical-summary recompute | campaign summary tamper detection (Q02, 2026-11-04) |
| M23 | WfCommons parser skips schema-version and unknown-field validation | WfFormat 1.5/1.6 input gate (Q05, 2026-11-04) |
| M24 | Space-Shared drops the file-transfer MI added to Cloudlet length | CloudSim 3.0.3 transfer-work accounting (B02, 2026-11-04) |
| M25 | FutureQueue assigns serial zero to every event | same-time event ordering (B02, 2026-11-04) |
| M26 | WorkflowDatacenter drops the SHARED input-transfer seconds | legacy WorkflowSim transfer envelope (B03, 2026-11-04) |
| M27 | WfCommons runtime conversion collapses every task to the minimum length | Montage real-derived runtime mapping (B05, 2026-11-04) |
| M28 | Exact Wilcoxon reverts to the Commons Math 3.2 rank-sum path that discards ties | actual-rank exact statistical verification (B12, 2026-11-04) |
| M29 | Completion guard ignores the configured minimum event interval | tick semantics (B4, 2026-11-04) |
| M30 | Workbench drops the event-recorder cap from the simulation allowlist | experiments configuration surface (B4, 2026-11-04) |
| M31 | Coherent dataflow runtime reports zero admitted copies | profile semantic-fingerprint and copy-count verification (B5, 2026-11-04) |
| M32 | CaWoSched green-window selection degrades to ASAP | paper algorithm reproduction (R-PAPER-01, 2026-11-05) |
| M33 | CaWoSched power weighting is removed from slack scores | generic timed-plan variants (R-PAPER-01B, 2026-11-05) |
| M34 | Communication-enhanced DAG silently drops a missing link | communication model (R-PAPER-01C, 2026-11-05) |
| M35 | Exact single-processor DP degrades to the slack greedy plan | exact oracle (R-PAPER-01D, 2026-11-05) |

The set is deliberately small and **registered**: it is a canary battery over the semantic
axes that carry research claims, not an exhaustive mutation score for the whole codebase.
One semantic axis, one canary; growth is by deliberate ledger addition (policy below).
The M12–M19 growth (2026-11-03, AUD-F02) registered one canary per semantic axis that this
audit-fix effort introduced, each verified KILLED red-first at the killers stage; the F-19
logging channel stays unregistered by argument — log routing is diagnostics visibility,
not a research-claim decision surface, and `LogPrintErrorTest` guards it directly. The M20
addition (2026-11-04, post-completion review follow-up) registers the runner-wiring axis of
the AUD-F07 cap configuration outlet: the runner must construct the recorder from the
configured cap, not silently fall back to the default; the post-commit battery of 2026-11-04
verified the set 20/20 KILLED (M20 at the killers stage). The M21 addition (2026-11-04,
same review) registers the AUD-F23 declared-revision-contract axis: a marker-emitting writer
declares the contract, so ignoring the declaration would let a stripped stream pass as a
legacy one; M21 was verified KILLED red-first at the killers stage (the two declared-contract
tests fail with the declaration ignored). The full battery of 2026-11-04 then ran all 21
canaries green: 21/21 KILLED, 0 survived, 0 stale, 0 infra-failure, 437 s wall clock.
The M22 addition (2026-11-04, Q02) registers the campaign-summary evidence axis: bypassing
`ExperimentCampaignSummaryVerifier` must make the intact-per-run/tampered-summary regression
fail at the killers stage. The M23 addition (2026-11-04, Q05) registers the WfFormat input
gate: bypassing `WfCommonsSchemaValidator` must make the unknown-version and unknown-field
regressions fail. M24–M26 (2026-11-04, B02/B03) add the fixed-commit CloudSim and legacy
WorkflowSim reference axes: transfer-work accounting, same-time serial ordering, and the SHARED
transfer envelope. Each is killed by its corresponding hand-derived reference test. M27
(2026-11-04, B05) adds the Montage real-derived runtime-mapping axis: collapsing every task to
100 MI must be killed by the simulator-module `WfCommonsRuntimeReferenceTest`; the separate
17-instance structural suite remains in experiments. M28 replaces the exact Wilcoxon
implementation with the old Commons Math 3.2 rank-sum path, and M29 removes the configured
minimum event interval from completion timing; both are killed by independent reference tests.
M30 is registered in the separate experiments family and kills the Workbench event-cap
allowlist omission. M31 (2026-11-04, B5) adds the profile semantic-fingerprint axis: a false
admitted-copy count must be rejected by the independent comparator, not hidden by timing. M32
(2026-11-05, R-PAPER-01) adds the CaWoSched green-window selection axis. M33 (R-PAPER-01B)
adds the power-weighted slack-score axis. M34/M35 (R-PAPER-01C/D) add missing-link and exact-DP
axes.

## Usage

```bash
# Simulator family (~5–10 min for the 34 registered canaries; requires a clean Git tree and exclusive run — no parallel builds)
python3 -B scripts/mutation-gate.py --output <new-report.json>

# Experiments family (one Workbench canary, same clean/exclusive requirement)
python3 -B scripts/mutation-gate.py --module experiments --output <new-experiments-report.json>

# Both families sequentially (35 canaries total)
python3 -B scripts/mutation-gate.py --all-modules --output <new-report.json>

# Ledger + anchor liveness only (no mutation, no Maven; cheap periodic preflight)
python3 -B scripts/mutation-gate.py --output <new-report.json> --dry-run

# Subset by id
python3 -B scripts/mutation-gate.py --output <new-report.json> --ids M5,M9

# Convenience target (report lands in the gitignored engineering archive)
make mutation-gate

# Self-tests (no Maven involved)
python3 -B scripts/test-mutation-gate.py
```

Exit codes: **0** all registered mutants KILLED; **1** a mutant SURVIVED or the build
infrastructure failed while the mutant was applied; **2** at least one registered anchor is
stale (source drifted; the ledger must be updated first). The report is JSON, written only
to a path that does not yet exist (never-overwrite semantics, same discipline as the
independent auditors).

CI runs both registered families on every push and pull request in the dedicated `mutation-gate`
job ([ci.yml](<../../.github/workflows/ci.yml>), AUD-F02 2026-11-03; experiments scope added
in B4). The job's clean checkout satisfies the clean-tree precondition, the dedicated runner
satisfies the exclusive-run rule, and both JSON reports are uploaded as build artifacts. Local
runs remain the entry point for ledger work and the `--dry-run` anchor preflight.

## Execution model and safety

- Each mutant is applied **in place** to the working tree; every target file is restored
  from an in-memory backup in a `finally` block and re-verified byte-equal after restore.
- Full mode refuses to start when `git status` is dirty.
- Per mutant, the registered **killer test set** runs first (fast); if it does not kill, the
  corresponding full module battery runs as escalation (`:workflowsim`, or
  `:workflowsim-workbench -am` for the experiments family). KILLED is claimed only on Maven exit
  non-zero **with** test failures; a non-zero exit without failures is an INFRA_FAILURE,
  not a kill.
- Anchor uniqueness: each registered anchor must occur **exactly once** in the current tree.
  Style-only refactors therefore make an anchor stale — the gate exits 2 and points at the
  mutant id; update the anchor to the current text (drift repair is not a semantic change).
- Interruption recovery: the clean-tree precondition makes recovery deterministic — if the
  run is killed mid-mutant, the single dirty file left behind **is** the applied mutant;
  restore with `git checkout -- <file>` and re-run in the foreground. Do not run this gate
  as a killable background job.

## Adding a mutant (ledger policy)

1. Register in `REGISTRY` with: unique `M<n>` id, rationale-bearing `name`, target `file`,
   exact `old`/`new` anchor (unique in tree), and the killer test classes.
2. Demonstrate the kill **red-first**: run the gate for that id alone and record KILLED.
3. Extend this page's ledger table with the guarded axis.
4. If the anchor is later re-shaped by mechanical refactors, repair the anchor without
   changing the mutant's semantics; the dry-run preflight makes drift cheap to detect.

Adding a mutant to fix a *survived* canary means a gap was found: first write the failing
killer test (expectation from literature/contract/conservation law, per the repo's
three-source rule), then register the mutant that it kills.
