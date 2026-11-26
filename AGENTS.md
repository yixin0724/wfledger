# AGENTS.md — WfLedger Coding-Agent Handbook

> This repository is a **simulation and experimentation platform for the research
> workflow-scheduling problem** (single-workflow DAG scheduling plus multi-workflow
> peak-shifting contention).
> Scientific standard: the literature algorithms are implemented faithfully; experimental
> conclusions are **reproducible, traceable, and backed by a complete evidence chain**
> (internal validity; fidelity calibration against real clusters is explicitly not pursued).
> This file is mandatory reading for every agent before acting. Changing it requires human
> review — it is the "first day on the job" briefing for a new agent.

## Project facts (the five things agents most often get wrong)

1. **Maven multi-module reactor**: `simulator` (core, no upstream dependency) →
   `experiments` (Workbench + research drivers). The core module must never depend on the
   experiment module. (The third module `grouped-workflows` was removed on 2026-11-02 by user
   adjudication: verified zero references in the platform; its snapshot is preserved at git
   `6db21f1` and its research contract is managed in an external repository.)
2. **CloudSim 3 global-state kernel**: concurrent or parallel simulation inside one JVM is
   strictly forbidden. Every run must establish its session boundary through
   `SimulationSession` (try-with-resources).
3. Requires JDK 17+ and Maven 3.6.3+; the compiler targets Java 8 bytecode
   (`maven.compiler.release=8`).
4. The three workflow-input sources (Pegasus DAX, WfGen synthetic, WfInstances
   real-derived) have different evidence boundaries; **mixing them into one workload
   population is forbidden** (see `datasets/README.md`).
5. Git main-branch workflow; the review-engineering temporary workspace was removed
   entirely on 2026-11-02 (snapshot at git `6db21f1`) and its conclusions were folded into
   `docs/` — a deleted temporary directory never represents product state.

## Common commands (run these before touching anything)

```bash
mvn -q compile                                  # compile every module
mvn -pl :workflowsim test                       # simulator unit tests
make quality-gate                               # complete local acceptance (Maven + Python + docs + language + datasets)
mvn -Pjavadoc clean verify                      # the full CI gate (tests + coverage + Javadoc)
python3 -B scripts/check-docs.py                # documentation link/anchor check
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py   # Python auditor self-tests

# Workbench smoke run (the standard entry point)
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"
```

**Trap**: running the `experiments` module tests on their own always requires `-am`.
Without it Maven resolves a stale simulator jar from `~/.m2`, the provenance source-hash
domain differs, and the rerun tests fail spuriously. This is not a bug; do not "fix" it.

## Domain map (30-second version)

- Event chain: `WorkflowPlanner → ClusteringEngine → WorkflowEngine → WorkflowScheduler →
  WorkflowDatacenter` (root package `org.workflowsim`).
- Decisions split into three layers that **must not be compared across layers**: planning
  (`planning/`, offline Task → VM plus order), scheduling (`scheduling/`, online ready
  Job → VM), and coherent online binding (`DataflowVmAssigner` in `data/v2`).
- Nine data/network physical models (`DataMovementModel`); three evidence sidecars
  (network ledger, file lifecycle V2, storage lifecycle V3), whose format contracts live in
  `docs/advanced/`.
- **The contract-document system lives in `docs/`**: a behavior change must update the
  matching contract document and vice versa. Before entering the deep zones of the code,
  read `docs/README.md` to find the corresponding contract.

## The Do-Not list (violating it corrupts the science)

1. **Failure must be explicit**: no silent degradation, no fallback that keeps going, no
   swallowed exception followed by continuation. Fail fast and stop rather than emit
   misleading results.
2. **Test expectations have only three legal sources**: (1) hand derivation from primary
   literature (papers, textbooks); (2) the project's contract documents; (3) conservation
   laws and invariants. **Copying implementation output as an expected value is strictly
   forbidden**; the only exception is an explicitly labeled golden regression, whose job is
   to detect drift, not to prove correctness.
3. The `EvidenceCoreDiffer` whitelist **must not be widened casually**: a new exemption is
   an evidence-contract revision and must state its rationale in
   `docs/experiments/RERUN_DIFF_CONTRACT.md`.
4. **The events v1 event schema must not be changed unilaterally**: new event types or key
   fields require user adjudication.
5. Task clustering is **NONE only**: the legacy clustering family (Horizontal/Vertical/
   Block/Balanced) and the DC/SR/DR/BLOCK/VERTICAL reclustering modes were removed on
   2026-11-24 by user adjudication (recoverable from git history). `ReclusteringEngine`
   keeps only the NOOP and CRCH retry paths; do not reintroduce clustering without a new
   contract and user adjudication.
6. The deprecated scheduling labels (`MINMIN`/`MAXMIN`/`MCT`/`ROUNDROBIN`) and the RL demo
   track (`RL_POLICY`, `org.workflowsim.rl`) were removed on 2026-11-24; use the maintained
   `READY_BATCH_*` labels or the `SchedulingAlgorithmProvider` SPI instead.
7. Every mutable global static (`Parameters`/`FailureMonitor`/`ReplicaCatalog`/
   `NetworkTopology` and the rest, with the ledger frozen in
   `SessionResetMatrixTest.AUDIT_CLASSES`) must be accessed within a `SimulationSession`
   lifetime; before adding a global static, ask yourself why session injection is not used.
8. Randomness must go through the `SimulationRandom` named streams; bare `new Random()` and
   seed literals are forbidden.
9. **Floating-point determinism discipline**: collection traversal and summation on the
   evidence path keep a deterministic order (`LinkedHashMap`/`TreeMap`/ascending-ID
   tie-break); before changing anything, decide whether it introduces iteration-order
   drift.
10. The Java and Python auditors **must stay semantically aligned**: any change to a Java
    metric or event computation must be checked against the matching recomputation in
    `scripts/`.

## Style and language

- Code carries **no explanatory comments**. Public types and members keep a short javadoc
  only where the API contract is not obvious from the name and signature. Root causes,
  fix rationale, and history belong in commit messages and the contract documents, not
  in source (revised 2026-11-26 by user decision; the former F-xxx / R-round marker style
  is retired). The whole repository is English.
- Alias and mixed-language names already present inside dataset files keep their current
  form; new file names use ASCII. Dataset fixtures are frozen bytes: never edit text
  inside a `.dax` file, because recorded input identities would no longer match.

## Repository structure conventions (added by the 2026-10-20 review engineering)

| Directory / file | Role | Rules for additions |
|---|---|---|
| `simulator/` | Core engine (artifactId `workflowsim`, no upstream dependency) | Algorithms, models, evidence classes |
| `experiments/` | Workbench Maven module (artifactId `workflowsim-workbench`): unified entry point, research drivers, reproduction, examples | Reorganization starts only on external demand |
| `datasets/` | Workflow inputs (three families, with .gitignore negation) | No new full corpora; check `datasets/INDEX.json` first |
| `docs/` | Contract-document system | Every new document must be indexed in `docs/README.md`; `check-docs.py` 0 issues |
| `scripts/` | Python auditors (pure stdlib) | No Node dependencies; self-tests come in pairs |
| `config/` | Code tool-chain ledger (checkstyle tier rules, etc.) | Rules only grow; revise in step with CODE_STYLE.md |
| `output/` | Run artifacts (gitignored) | Keep only tar originals plus currently active experiments; old heaps must be cleaned |
| `AGENTS.md` | Coding-agent handbook | Changes require human review |
| `LICENSE` | Official GPL-3.0 full text | Must not be replaced with a permissive license: upstream CloudSim 3 carries copyleft notices (`simulator/src/main/java/org/cloudbus/`) |
| `NOTICE.md` | Copyright, source lineage, and modification notices | Preserve upstream notices and record WfLedger modifications |
| `THIRD_PARTY.md` | Third-party provenance and licensing ledger | Update whenever upstream code or dependency scope changes |
| `licenses/` | License texts and source hashes | Keep upstream notices, texts, and `provenance.json` synchronized |
| `CITATION.cff` | Software citation metadata (read by GitHub "Cite this repository") | Version, date, and author changes must match an actual release; the license field is fixed to `GPL-3.0-or-later` |
| `.editorconfig` | Editor encoding, line-ending, indentation, and trailing-whitespace defaults | Keep in step with Spotless and CODE_STYLE.md; no formatter is driven by this file alone |
| `Makefile` | Convenience commands | Maintained in step with the pom.xml targets |

**Discipline for adding a top-level directory or file**: first ask "why can this not live
in an existing directory"; if it must be added, update this table, `docs/README.md` (when
documentation is involved), and the `check-docs.py` result in the same change.

## Definition of done

- New logic: **write the failing test first** (with an expectation from one of the three
  legal sources) → fix → all green; the root cause goes into the commit message.
- Behavior change: the matching contract document is updated in the same change and
  `check-docs.py` reports 0 issues.
- Close-out: `mvn -Pjavadoc clean verify` is green; when the Python audit domain is
  involved, its self-tests are green too.
- Anything the user has not adjudicated (event schema, executing a cleanup, changing a
  contract's direction): **stay in proposal state** — never act first and report later.

## When you meet a "counter-intuitive" implementation

Assume first that it is **deliberate**: read the matching contract document and the git
history of that code (`git log -p`); if it still cannot be resolved, ask the user or file an
issue. **Do not "fix" it in passing** — much of what looks odd in this repository
is the scientific design itself (for example: whitelist regexes derived from constants,
comparators using `Long.compare`, the parser's two-phase commit).

## Deep-dive routes (load on demand, do not read everything at once)

- Contracts and boundaries: `docs/` (entry point `docs/README.md`)
- Algorithm semantics and the combination matrix: `docs/algorithms/CATALOG.md` +
  `CONTRACTS.md`
- Dataset rules: `datasets/README.md`
- Historical sediment (defect ledger, methodology): git history (the former `docs/history/`
  directory and the temporary review area were removed; do not recreate them)