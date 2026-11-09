# Fat-tree Historical Campaign: Provenance, Parameters, and Retention

> This directory holds the **historical numbers of the R8 recalibration run**; it is neither a certification nor a golden value for the current execution semantics. Later corrections to CPOP, network allocation, CPU work, and LOCAL data-availability time have changed some results; historical artifacts must not be overwritten, nor the new model adjusted, in pursuit of bit-for-bit agreement. For the later study identities and the validation boundary see [protocol revisions](<../PROTOCOL_COMPATIBILITY.md>).

## Retained Artifacts and Reference Period

- [campaign-results.json](<campaign-results.json>): schema `workflowsim-fattree-campaign-v1`, containing per-run summaries, weak-monotonicity diagnostics, rankings, and paired statistics.
- [campaign-results.md](<campaign-results.md>): the automatic numeric tables of the same historical run.
- The source is the R8 recalibration version of [FatTreeSchedulingCampaignExecutor](<../../src/main/java/org/workflowsim/experiments/fattree/FatTreeSchedulingCampaignExecutor.java>); the original record is dated 2026-09-16 at 13:42 (time zone not recorded), JDK 17, seed 91, and all 360 cells are `COMPLETED_SUCCESSFULLY`. The current source link identifies the driver; it does not imply that the current implementation matches the one at the time.

These artifacts are the numeric basis of the conclusions reached then; they are not a complete per-run manifest/metrics/events bundle. Both files are retained long-term, and the documentation cleanup neither regenerates nor rewrites the numbers in them.

## Frozen Matrix and Apparatus

The research question is how network constraints change the relative performance of scheduling policies. The 10 DAX inputs are Epigenomics n24/n46/n100, CyberShake n30/n50/n100, Inspiral n30/n50/n100, and the [HEFT ten-task input](<../../../datasets/dax/heft/heft-paper-example.dax>). Montage/Sipht were excluded because of same-name file size conflicts detected by the LOCAL planners; the thousand-task tier was not included.

The platform uses one VM per Host, with 3 VMs for the main matrix and the first sensitivity block and 4 VMs for the structural block; each VM runs at 1 MIPS with a 1 MB/s endpoint, SPACE_SHARED. It uses LOCAL/STATIC and NONE clustering, with no failure/overhead; no TaskCostMatrix is supplied, and the computation amount comes from DAX runtime normalization. This apparatus is not the explicit cost-matrix apparatus of the simulator paper-reproduction test.

| Block | Combinations | Run cells |
|---|---|---:|
| Main matrix | 10 DAG x LOCAL_HEFT/LOCAL_CPOP/RANDOM/PSO x V1/R2/R6 | 120 |
| 3-host sensitivity | 10 DAG x LOCAL_HEFT/LOCAL_CPOP x 6 topology variants, all R6 | 120 |
| 4-host structural sensitivity | 10 DAG x LOCAL_HEFT/LOCAL_CPOP x the same 6 variants, all R6 | 120 |

V1 is `PRE_EXECUTION_TRANSFER_DELAY_V1`, R2 is `PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1`, and R6 is `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1`; the names record the model branch at the time and do not imply that its later implementation is unchanged.

The link baseline is **0.125 MB/s** (1/8 of the VM endpoint) and A4 is **1.25 MB/s** (10 times the baseline). The 6 variants are:

| Variant | Topology parameters |
|---|---|
| baseline | k=4, full core complement, baseline links, default round-robin placement |
| A1 | k=4, 2 cores |
| A2 | k=8, 16 cores (full complement) |
| A3 | k=8, 8 cores |
| A4 | k=4 full complement, only the links changed to 1.25 MB/s |
| A5 | k=4 full complement, explicit same-edge host pairs; 3 hosts `{0->0,1->0,2->2}`, 4 hosts `{0->0,1->0,2->2,3->2}`, values are global edge indices |

Parameters are unchanged apart from the single-axis changes listed. By default the 3 hosts sit at pod0/edge0, pod0/edge1, and pod1/edge0 in that order; the 4th host is at pod1/edge1. All runs fix seed 91; the cross-DAG paired Wilcoxon, the per-DAG winner, and the weak monotonicity describe this apparatus only. Repeating a deterministic run is not a new independent sample, and neither cross-model makespan monotonicity nor structural-axis degeneration is a general theorem.

## Provenance Notes of the Recalibration

The first implementation mistakenly used Fat-tree links declared as 1.0 MB/s at 0.125 MB/s (an extra division by 8). After fixing the units, the historical apparatus was rerun with links/endpoints both at 1.0 MB/s, and results appeared in which R6 equals R2. The baseline was then explicitly declared as 0.125 and A4 as 1.25 MB/s, forming the **version retained in this directory**; this records a parameter change and its origin, not a unit change made to hide physical parameters.

That several old R6 numbers agree again after the recalibration does not mean the later corrected model should also agree. The structural-axis degeneration at both host scales and the rankings/statistics of the time may be cited only as the reference period of this directory; the conclusions of the old 360 cells must not be generalized to results of the current implementation.

## Difference Analysis under the Current Code

The command below uses the **current implementation** with the historical parameters from the driver to generate new results; it does not restore the R8 binaries. Use a brand-new output directory, then compare and explain the differences; do not point it at this directory.

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor \
  -Dexec.args="/absolute/datasets /absolute/new-fattree-comparison"
```

The current tests constrain only their declared inputs and model version and cannot require the new output to equal the old artifacts as a whole. Temporary comparison outputs and formally retained evidence are managed separately; the two original numeric artifacts in this directory are not cleanup targets.
