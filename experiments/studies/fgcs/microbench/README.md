# FGCS discriminating model microbenchmarks

## Purpose and oracle boundary

These six small **actual `SimulationRunner` executions** discriminate four model contracts that aggregate FGCS matrices cannot validate by themselves. Every expected number is derived before execution from a contract or conservation law; no current simulator output is used as a golden expectation. The generated `results.json` is a compact, reproducible observation, not the source of the test oracle.

| Case | Runs | Hand-derived discriminating expectation |
|---|---|---|
| Two-flow bottleneck | `bottleneck-shared`, `bottleneck-independent` | Two 2 MB and 6 MB flows start together at a 1 MB/s bounded-store read. Max-min allocates 0.5 MB/s each: the small flow ends at 4 s; the large flow has 4 MB remaining, is refilled to 1 MB/s, and ends at 8 s. Independent paths retain the same single-flow bottleneck and end at 2 s and 6 s. A fixed-capacity/n model would wrongly predict 4 s and 12 s. |
| Replica visibility / CPU barrier | `replica-visibility` | A remote child needs 1 MB and 4 MB inputs on independent 1 MB/s paths. Effective service is 1 s and 4 s. The fast replica settles first, but exactly one data-ready release occurs with the slow settlement, and CPU start cannot precede it. |
| Required output write-back | `writeback-drain`, `writeback-control` | An unused successful 1 MB output takes 2 s at the 0.5 MB/s store write bottleneck. CPU return does not wait, simulation end does, and terminal drain is positive. The coherent-file V2 control has no output copy and zero terminal drain. |
| Parent failure / CRCH retry | `crch-retry` | A fixed-seed, VM-and-depth-keyed injection fails only the 2000 MI parent once. Its observed envelope must satisfy `1.1 <= elapsed < 2.2`, so one checkpoint saves 1000 MI. Retry work is `1000 remaining + ceil(0.1*1000) overhead = 1100 MI`, taking 1.1 s at 1000 MI/s. The retry avoids the failed VM; child `JOB_READY` follows the successful parent return, never the failed return. |

Network expectations use `COPY_ADMITTED.observedTime` and `COPY_SETTLED.payload.effectiveTime`. The later externally observed settlement is kernel dispatch evidence, not a license to quantize or tune the analytic service expectation. CPU and dependency checks use event sequence and observed visibility boundaries. CRCH work and model-derived Task windows are checked independently of failure random-number replay.

## Fail-fast validity rules

The driver aborts and removes the previous `results.json` before execution if a fixture becomes invalid: a missing or ambiguous transfer, local instead of remote replica mapping, incomplete workflow or truncated lifecycle, no/extra parent failure, elapsed time outside the one-checkpoint interval, wrong retry work, premature child release, or any expected/observed mismatch. It does not relax an expectation, increase the retry budget, skip a failed case, or write a passing report. Generated DAX inputs under `inputs/` are deterministic and belong to this microbenchmark population only.

## Run

From the repository root:

```bash
mvn -q -pl :workflowsim-workbench -am \
  -Dtest=ModelMicrobenchmarksTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The additional Surefire property only prevents upstream reactor modules with no matching test from rejecting the selected test name; it does not skip a test or simulation. Generate the compact report, deterministic inputs, and event excerpts under the ignored `paper/tmp/fgcs-revision/microbench/` workspace with:

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.microbench.ModelMicrobenchmarks
```

An optional first argument selects a different output directory. This protocol directory intentionally contains no generated inputs, results, or event artifacts.

## Evidence layout

`results.json` contains four case objects, each with `expected`, `observed`, a textual `derivation`, and `evidence` references. Each reference is a relative file plus a JSON pointer into the selected full event payload, for example:

```text
bottleneck-shared.events.json#/events/largeSettled/payload/effectiveTime
crch-retry.events.json#/events/retryTaskWindow/attributes/effectiveExecutionLengthMi
crch-retry.events.json#/events/childReady/sequence
```

The six `*.events.json` files retain only the events needed to audit the reported quantities. Tests resolve every pointer and independently repeat the numeric and causal assertions against the in-memory `SimulationReport` objects. Contract sources are the coherent-file, storage-dataflow, and CRCH sections listed in the report's `contracts` array. These cases establish internal model validity only; they are not real-network calibration or a cross-model performance ranking.
