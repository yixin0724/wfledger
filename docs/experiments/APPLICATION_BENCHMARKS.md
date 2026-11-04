# Workflow Application Structural Benchmarks

This page records the application-input verification baseline and its evidence boundaries. It
covers DAG conversion and declared input semantics, not source-platform replay, algorithm
correctness, or real-cluster calibration.

## Populations and provenance

| Population | Source and fixed identity | Verification scope |
|---|---|---|
| Classic DAX | 20 retained Pegasus DAX inputs under `datasets/dax`; the independent `dax/heft` paper fixture is not counted among the 20 | XML job count, parser task count, DAG validity, and input format |
| Montage WfInstances | [WfInstances v1.5](https://zenodo.org/records/12510982), 17 JSON instances under `datasets/wfinstances/v1.5/pegasus/montage` | Specification/execution task counts, DAG, runtime-to-MI conversion, file identity/bytes, schema version, and explicitly missing execution fields |
| Historical application profiles | [Bharathi et al. 2008](https://doi.org/10.1109/WORKS.2008.4723958) and [Juve et al. 2013](https://doi.org/10.1016/j.future.2012.08.015) | Candidate structure/runtime/file-profile references for future hand-derived cases; not production traces |

The three input families remain separate. DAX synthetic inputs, WfGen synthetic inputs, and
WfInstances real-derived inputs are never merged into one workload population. Montage 2MASS and
DSS subsets also retain their source labels.

## Test coverage

- `ClassicDaxStructuralTest`: all 20 classic DAX files, with job counts computed independently
  from the XML namespace and checked against the parser and DAG validator.
- `MontageWfInstancesStructuralTest`: all 17 Montage files, with expected values computed from
  the original JSON. The 58-task 2MASS example is included, as are the large 2MASS/DSS instances.
- `WfCommonsJsonParserValidationTest`: 1.5/1.6 acceptance, unknown-version and unknown-field
  rejection, plus Nextflow `repo`/`runName` provenance compatibility.

The Montage snapshot has no `coreCount`, `readBytes`, or `writtenBytes` fields in its execution
tasks. Those values are therefore **unknown**, not zero and not inferred from file sizes. A
makespan or sum of task runtimes is not used as a source-platform expectation.

## Runtime and file units

The parser uses `runtimeInSeconds` and `sizeInBytes` directly. At the standard reference setting
`runtimeReferenceMips=1000` and `runtimeScale=1`, each task's expected length is independently
computed as:

```text
max(100, floor(runtimeInSeconds × 1000))
```

File sizes remain bytes. A runtime conversion is not a claim that the source machine and the
simulated VM have identical capacity. The M27 canary collapses all runtime values to 100 MI and
must be killed by the 17-instance suite.

## Out of scope

No current assertion claims a real-platform makespan match, a complete WMS/Pegasus replay, a
real-network measurement, or a production scheduler ranking. Those require a separate referent
with machine, scheduler, clustering, network, WMS, and measurement-uncertainty records. See
[V&V case and golden-evidence ledger](<VV_CASE_LEDGER.md>) and
[fixed-commit reference implementation](<REFERENCE_IMPLEMENTATION.md>).
