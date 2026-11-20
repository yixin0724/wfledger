# Same-Layer Static Planner Baseline

This study remedies the earlier single-static-baseline limitation by comparing three static planners within one decision layer and physical model.

- Algorithms: SHARED_STORAGE_HEFT, SHARED_STORAGE_CPOP, SHARED_STORAGE_PEFT.
- Inputs: Montage 50/100, CyberShake 50/100, SIPHT 60, Inspiral 50, Epigenomics 46 classic Pegasus characterization DAX instances.
- Platform: SHARED legacy data movement, four SPACE_SHARED VMs at 500/1000/1500/2000 MIPS, storage 15 MB/s, one Task per Job, NONE clustering.
- Runtime reference: 1000 MIPS, seed 7.
- Matrix: 7 workflows × 3 static planners = 21 completed runs.

The earlier 42-run baseline contains HEFT plus five online policies. Its “HEFT best static” observation is not a static-algorithm tournament. The present matrix is a same-layer comparison; it is not merged with online ready-job schedulers into a single winner population.

The driver and tests are in `org.workflowsim.experiments.fgcs.baseline`. Each result includes mapping, makespan, waiting, slowdown, energy, cost, and independent audit residuals. Results are generated under `paper/tmp/fgcs-round2/static-baseline/`. The platform is obtained directly from `MakespanBaselineComparison.platform()`, so the VM identifiers, prices (1/2/4/8 cost units per second), and all other declarations match the original baseline exactly. An earlier draft used a 1/2/3/4 price profile and was not a valid cost comparison.

Run from the repository root:

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.baseline.StaticPlannerBaselineStudy
```

No real-cluster calibration, universal ranking, or comparison with the static independent-task MINMIN family is claimed; the latter rejects DAG dependencies and belongs to a different execution contract.
