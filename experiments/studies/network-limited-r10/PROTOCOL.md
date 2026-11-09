# R10 Historical Study Provenance and Eligibility Notes

> This page documents only the retained `network-limited-r10-v2` study; it is not the current model protocol. The current executor produces `network-limited-r10-v3`; for how to run it and the certification scope see [protocol revisions](<../PROTOCOL_COMPATIBILITY.md>). The old data is neither rewritten nor relabelled as a new execution model.

## Provenance of the Frozen Study

The driver is `org.workflowsim.experiments.network.NetworkStudyExecutor`. The historical study compared static scheduling policies under endpoint constraints, path-link constraints, and changes in resource scale, using the CPOP predecessor rank and the max-min/piecewise integration as corrected at the time, but predates the later corrections to CPU work and LOCAL data-availability time.

| Condition | Historical declaration |
|---|---|
| Qualified inputs | Epigenomics 100/997, CyberShake 100/1000, Inspiral 100; plus four-layer synthetic DAGs with 32/128 tasks and 32 MB per file edge |
| Resources | 4/16 VMs; 1000 MIPS, 1 MB/s endpoint; one VM per Host, fixed placement; Fat-tree k=4 |
| Network | endpoint contention; 0.125 MB/s constrained Fat-tree; 1.25 MB/s wide-link Fat-tree; all start transfer at Job-ready |
| Algorithms/seeds | LOCAL_HEFT and LOCAL_CPOP once per condition (11); RANDOM and PSO each use 11/29/47/71/101 |
| Run conditions | LOCAL, STATIC, SPACE_SHARED; no failure, no extra overhead |
| Full matrix | `7 x 2 x 3 x (1 + 1 + 5 + 5) = 504` run cells |

The primary metric is simulation end time; secondary metrics are logical completion, mean/P95 waiting, and VM utilization. Random repetitions are aggregated within the same DAG/resource/network/algorithm first, then paired by DAG; the classic and synthetic sources are kept separate, using an exact two-sided sign test and a Holm adjustment for the three-candidate comparison family. The sample is fixed and comprises only 5 classic/2 synthetic DAGs; non-significance is not equivalence, and there is no extrapolation to a real platform. The LOCAL list planners carry a per-VM order, whereas RANDOM/PSO only map, so the comparison covers the complete policy pipeline.

## Eligibility Exclusion and Failed Pilot

Eligibility checks address the parsed input structure and the file declarations, and do not screen after observing algorithm outcomes. For duplicate INPUT sizes, the DAX handling applies a compatibility conversion that "prefers the first declaration and warns"; CyberShake contains such declarations, so the study inputs cannot be described as unambiguous in every original declaration. The original files and their hashes are not modified.

Inspiral1000's `H1-THINCA-782406919-2048.xml` still has a 39368/46451-byte conflict after parsing, which is incompatible with the LOCAL planners' file model. In the first 576-cell pilot, two LOCAL planners x 2 resources x 3 networks produced 12 failed runs in total, and the validator refused a complete comparison. Formal v2 excludes the entire input uniformly for all planners and also excludes the random methods' successful results on that input.

## Optional Retention Locations and Handover

The paths below are relative to the project root and record the retention locations in the original workspace; **a normal checkout does not include these files, and their presence on the current machine is not guaranteed**:

- Failed pilot: `output/network-study-r10/`.
- Formal study: `output/network-study-r10-final/`, containing the original `protocol.json`, `network-study.json`, the automatic `results.md`, the synthetic inputs, and 504 per-run v4 manifest/metrics/events.
- Complete handover package: `output/network-study-r10-evidence.tar.gz`; the SHA-256 in the original handover record is `38d9f8bfd42743706a1719f0942fa07733a3147b739a7332ebf39142edc5a2c3`, which this documentation cleanup did not recompute.

Given the original evidence, `NetworkStudyValidator` can be invoked on its index; that check proves the consistency of the registered historical declaration and the artifacts, not that the current code reruns identically. The current executor command only produces the new protocol and must use a new output directory; the original data must not be overwritten and parameters must not be adjusted to preserve the old ranking. A complete handover must include the original protocol bytes and every referenced artifact; handing over the result table alone is not enough.

The impact of the later corrected model and the retained results are given in the [study impact recheck](<../correctness-repair/RESULTS.md>); other historical evidence is listed in the [studies directory](<../README.md>).
