# FGCS Upstream Differential Study

## Scope and outcome

This study compares 12 paired observations from the current FGCS exporter and the original
WorkflowSim 1.0 implementation. It is a **narrow differential observation**, not a universal
correctness oracle, a proof that either implementation is defective, or validation against a real
cluster.

The retained comparison passes its independent checks: all 12 cases have exactly the DAX-derived
logical task set with successful status on both sides, and neither side has a parent/child Job
dependency violation. Mapping, task-order, and makespan differences remain visible and are not
converted into pass/fail expectations.

Tracked files:

- [compare.py](compare.py): comparison, DAX invariant verifier, and optional full reproduction driver.
- [comparison.json](comparison.json): deterministic 12-case report bound to the retained observation SHA-256.
- [upstream-lock.json](upstream-lock.json): exact upstream commit, archive URLs, hashes, sizes, and tree comparison.
- [UpstreamRunner.java.txt](UpstreamRunner.java.txt): minimal adapter compiled against the separately downloaded upstream tree.
- [test_compare.py](test_compare.py): hand-built contract and mutation tests; simulator output is not an expected oracle.

The raw observations and downloaded/compiled third-party tree remain under ignored `paper/tmp`; no
third-party source or large generated observation file is added to the tracked study.

## Source identity

The source is [WorkflowSim/WorkflowSim-1.0](https://github.com/WorkflowSim/WorkflowSim-1.0), commit
[`d3ea21afd8ce6479bd292d3bd7469045d7a36089`](https://github.com/WorkflowSim/WorkflowSim-1.0/commit/d3ea21afd8ce6479bd292d3bd7469045d7a36089).
`git ls-remote ... refs/heads/master` resolved to that commit on 2026-11-18.

| Archive role | URL | SHA-256 | Bytes |
|---|---|---|---:|
| Original local observation archive | `https://github.com/WorkflowSim/WorkflowSim-1.0/archive/master.zip` | `34b58fd354421ba085bff4b6f8e25f2a8aaf835902893d35a3ebe1d740441cc5` | 14,480,849 |
| Immutable reproduction archive | `https://codeload.github.com/WorkflowSim/WorkflowSim-1.0/zip/d3ea21afd8ce6479bd292d3bd7469045d7a36089` | `fcf4c1b2cb8fe55e0949e08f434821f0c1fe9c4ed46d3b2fd09bd76ce6e9cae5` | 15,356,621 |

Both ZIP comments name the same commit. All 12,755 regular files have identical relative paths and
SHA-256 content hashes after removing the archive root directory. The different container hashes
therefore reflect ZIP/root metadata, not different source bytes. The branch URL is mutable and is
recorded only to identify the original bytes; reproduction always uses the commit URL and fails on
any size or hash mismatch.

The retained paired observation input has SHA-256
`e0094ddbcf5bef6ccd96d9ebf432ed7dd0670cc4c7672adada5e9d11dccafd67`. Each DAX SHA-256 is recorded
per case in [comparison.json](comparison.json).

## Matched protocol and semantic limits

| Condition | Current side | Upstream side |
|---|---|---|
| Cases | chain, fan, independent, Montage 50, CyberShake 50, SIPHT 60 × two scheduler labels | Same DAX bytes and case labels |
| Online scheduler | `READY_BATCH_MINMIN` or `STATIC` | adapter maps `READY_BATCH_MINMIN` to legacy `MINMIN`; `STATIC` is direct |
| STATIC planner | `SHARED_STORAGE_HEFT` | classic `HEFT` with point-to-point communication estimates |
| File system / movement | `SHARED`, explicitly selected `legacyWorkflowsimV1()` in the current engine | original upstream `SHARED` and legacy engine behavior |
| Platform | four SPACE_SHARED VMs, 500/1000/2000/4000 MIPS, one PE each | same MIPS sequence and ratio two |
| VM identity | IDs 1–4 | IDs 0–3; comparison normalizes upstream ID + 1 |
| Other conditions | NONE clustering, zero overhead model, current seed label `s7` | NONE clustering, zero overhead adapter configuration |

The HEFT rows are **not like-for-like planner equivalence tests**. Current `SHARED_STORAGE_HEFT`
uses shared-storage input-delay envelopes and zero point-to-point communication rank terms;
upstream classic HEFT uses its own communication and planning semantics. Likewise, selecting the
current legacy data-movement model does not make the entire current runtime bit-identical to the
old engine. Stage-in/release cadence, VM-start accounting, kernel event timing, planning, and tie
handling can all affect observations. Consequently, a HEFT residual or mapping difference is a
semantic/planning finding to investigate, never bug proof by itself.

For MinMin, planner selection is invalid on both sides. Its rows are therefore closer runtime
comparisons, but still not an assertion that legacy `MINMIN` and maintained `READY_BATCH_MINMIN`
are universally interchangeable. The `s7` label belongs to the current configuration; it is not a
claim that every upstream global random source was independently seeded to seven.

Makespan below is the maximum observed compute-Job `jobFinish`. “Task order” is the exporter's
observed Job/task emission order, not a topological-correctness metric. Upstream `Task.finish` and
`Job.jobFinish` are separate fields and are not forced to be identical. In particular, upstream
CyberShake STATIC tasks 5 and 35 differ across those fields by 0.0005 s and 0.001 s respectively;
both records have successful status and are included. Dependency checks consistently compare a
child's start with its parent's **Job** finish, and makespan consistently uses Job finish.

## Results

Times are seconds; delta is current minus upstream. Tables display six decimal places, while the
JSON retains raw observed values and exact decimal subtraction. “Complete C/U” means exact DAX
coverage plus successful status for current/upstream. “Dependency violations C/U” counts edges
where the child starts before the parent Job finishes (numeric tolerance `1e-7` s).

| Case | Current makespan | Upstream makespan | Delta | Mapping after +1 | Order matches | Complete C/U | Dependency violations C/U |
|---|---:|---:|---:|---:|:---:|:---:|:---:|
| chain-READY_BATCH_MINMIN-s7 | 25.876250 | 25.793750 | 0.082500 | 4/4 yes | yes | yes/yes | 0/0 |
| chain-STATIC-s7 | 25.986250 | 25.986250 | 0.000000 | 4/4 yes | yes | yes/yes | 0/0 |
| fan-READY_BATCH_MINMIN-s7 | 23.309500 | 23.227000 | 0.082500 | 4/4 yes | yes | yes/yes | 0/0 |
| fan-STATIC-s7 | 23.352750 | 23.352750 | 0.000000 | 4/4 yes | yes | yes/yes | 0/0 |
| independent-READY_BATCH_MINMIN-s7 | 80.476000 | 80.393500 | 0.082500 | 4/4 yes | yes | yes/yes | 0/0 |
| independent-STATIC-s7 | 15.719750 | 15.719750 | 0.000000 | 4/4 yes | no | yes/yes | 0/0 |
| Montage_50.dax-READY_BATCH_MINMIN-s7 | 117.990500 | 117.908000 | 0.082500 | 50/50 yes | yes | yes/yes | 0/0 |
| Montage_50.dax-STATIC-s7 | 103.965750 | 103.952000 | 0.013750 | 35/50 no | no | yes/yes | 0/0 |
| CyberShake_50.dax-READY_BATCH_MINMIN-s7 | 3185.770000 | 3185.637500 | 0.132500 | 50/50 yes | yes | yes/yes | 0/0 |
| CyberShake_50.dax-STATIC-s7 | 3138.835000 | 5976.699750 | -2837.864750 | 15/50 no | no | yes/yes | 0/0 |
| Sipht_60.dax-READY_BATCH_MINMIN-s7 | 8888.735500 | 8888.591000 | 0.144500 | 56/58 no | no | yes/yes | 0/0 |
| Sipht_60.dax-STATIC-s7 | 2033.033250 | 2066.482250 | -33.449000 | 53/58 no | no | yes/yes | 0/0 |

Aggregate observations:

- All 12 cases complete on both sides; total parent/child Job dependency violations are 0 current
  and 0 upstream.
- Normalized mappings match in 8/12 cases and exporter task order matches in 7/12. Only the three
  small-DAG HEFT makespans agree within `1e-9` s (the largest displayed residual there is `1e-15`).
- Five of six MinMin mappings match exactly. SIPHT MinMin differs only on tasks 6 and 9 (56/58
  matches). MinMin absolute makespan deltas range from 0.0825 to 0.1445 s. The repeated 0.0825 s
  offset coincides with the observed first-start cadence (current 0.21 s versus upstream 0.1275 s);
  it is not promoted to a universal VM-boot constant.
- The three small-DAG HEFT mappings and makespans agree. Independent HEFT emission order differs
  despite that agreement, which is why order is reported separately and is not an invariant failure.
- Real-workflow HEFT differences are substantial and explicitly semantic/planning observations:
  Montage matches 35/50 mappings with delta +0.01375 s; CyberShake matches 15/50 with delta
  -2837.86475 s; SIPHT matches 53/58 with delta -33.449 s. These numbers do not establish which
  planner is “correct” because their planner and runtime contracts differ.

## Reproduce or verify

Run from the repository root with Python 3. The ordinary verification path needs no network, Maven,
or Java execution:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 experiments/studies/fgcs/differential/compare.py \
  --observations paper/tmp/experiments/differential/upstream-observations.json \
  --output experiments/studies/fgcs/differential/comparison.json
PYTHONDONTWRITEBYTECODE=1 python3 experiments/studies/fgcs/differential/test_compare.py -v
```

The verifier derives its expectations from the registered 12-case matrix and DAX graph contracts.
It rejects duplicate JSON members, missing/extra/duplicate cases or tasks, non-success status,
invalid VM IDs/times, and parent/child violations. It does **not** require equal makespans, mappings,
or emission orders. The tests use a hand-built two-task graph and mutations for coverage, success,
VM normalization, ordering, and dependencies.

A full rerun explicitly opts into network and build work. It requires JDK 17+, Maven 3.6.3+, and a
**new or empty** work directory:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 experiments/studies/fgcs/differential/compare.py \
  --reproduce --work-dir paper/tmp/fgcs-differential-reproduction
```

For an offline reproduction, set `FGCS_UPSTREAM_ARCHIVE` to a local copy of the immutable commit archive. The supplied archive is still checked against the locked byte count and SHA-256 before extraction.

That command performs the following fail-fast steps:

1. Download the immutable commit archive, verify its exact size/SHA-256, and safely extract it.
2. Compile upstream sources plus [UpstreamRunner.java.txt](UpstreamRunner.java.txt) against the upstream
   bundled JARs, without modifying the current simulator.
3. Run the existing current `UpstreamDifferentialExporter` through Maven with
   `-pl :workflowsim-workbench -am compile exec:java`.
4. Invoke each upstream case in a fresh JVM (required by CloudSim 3 global state), merge the two
   observation sides, verify the DAX invariants, and write `comparison.json` in the work directory.

A fresh work directory prevents stale extracted sources or observations from being silently reused.
The raw generated files stay under ignored `paper/tmp`; only the compact report and reproducibility
code belong in this tracked study.
