# Fixed-Commit Reference-Implementation Verification

This document defines the narrow intersection where upstream source code can provide an external
verification oracle. It is not a real-platform validation campaign and not a license to restore
old WorkflowSim defects. Expected values are independently derived from the cited source and
registered project contracts; production output is not copied into an expected value.

## Source identities

| Reference | Fixed identity and access | Role |
|---|---|---|
| CloudSim 3.0.3 | [Cloudslab commit `46c46604a021c4cbb54a4dac812e91f89d6c495c`](https://github.com/Cloudslab/cloudsim/commit/46c46604a021c4cbb54a4dac812e91f89d6c495c), tag `cloudsim-3.0.3` | Kernel event ordering, Space-Shared work accounting, and the minimum event interval |
| WorkflowSim 1.0 | [WorkflowSim commit `d3ea21afd8ce6479bd292d3bd7469045d7a36089`](https://github.com/WorkflowSim/WorkflowSim-1.0/commit/d3ea21afd8ce6479bd292d3bd7469045d7a36089) | DAX conversion, stage-in, and the legacy SHARED transfer envelope |

The WorkflowSim snapshot is later than the 2012 paper and is no longer maintained. Its mixed
license notices and bundled CloudSim files require a separate license review before copying code;
this project uses source reading and independent tests, not wholesale source import.

## CloudSim cases

### Event time and serial order

`FutureQueue.addEvent` assigns increasing serial values and `SimEvent.compareTo` orders by
`(eventTime, serial)`. Three events inserted at times 2, 1, and 2 must be returned at times 1, 2,
and 2, with the two latter events in insertion order. `CloudSim.send` does not apply a blanket
minimum-interval filter: a 0.01 s send remains queued at 0.01 s. The minimum event interval is
used at processing/completion boundaries, not as a network-latency calibration.

The guard is `CloudSimKernelReferenceTest`; M25 resets every serial to zero and must be killed.

### Space-Shared work

For a 400000 MI, one-PE Cloudlet at 1000 MIPS and a 10 s file-transfer estimate:

```text
extra MI = 1000 × 10 = 10000 MI
recorded length = 400000 + 10000 = 410000 MI
ideal submission envelope = 410 s
```

`ResCloudlet` initialization also reads `Cloudlet.getCloudletFinishedSoFar()`. With no resource
binding, that API reports the raw 400000 MI as finished, leaving 10000 MI of transfer work. After
0.1 s of progress, 9900 MI remains and the next predicted completion is 10.0 s. This is a source
boundary, not an assertion that all 410000 MI starts unfinished. The guard is
`CloudletSchedulerReferenceTest`; M24 drops the transfer-length update and must be killed.

## Legacy WorkflowSim SHARED case

Use one 1000-MIPS VM, one PE, a 2 s root task, and one 150000000-byte external input at 15 MB/s:

```text
raw task length = 2000 MI
transfer seconds = 150 / 15 = 10 s
transfer work = 1000 × 10 = 10000 MI
execution envelope = 12 s = 12000 MI / 1000 MIPS
stage-in + release cadence = 0.21 s
expected simulation end = 12.21 s
```

The expectation is derived from upstream `WorkflowParser`, `ClusteringEngine`,
`WorkflowDatacenter`, and CloudSim's Space-Shared rules. It covers only
`LEGACY_WORKFLOWSIM_V1` + SHARED, not max-min contention, Fat-tree, V2/V3, old scheduling labels,
or failure/retry behavior. The guard is `ControlledSharedStorageIndependentReferenceTest`; M26
removes the transfer seconds and must be killed.

## Explicit non-goals

- No comparison of complete upstream examples or final makespans outside the declared intersection.
- No restoration of silent degradation, static-state pollution, old scheduler aliases, or known
  upstream failure-order defects.
- No claim that agreement with another simulator proves fidelity to a production cluster.
- No use of an upstream README's partial output as a complete, input-hashed golden.
