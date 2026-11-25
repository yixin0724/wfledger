# 1000 and 5000 Task Makespan Comparison (2026-11-09)

> Recorded before FGCS-R3 (2026-11-24). Online rows use the former read-free estimate and
> event-order-dependent rounds; they must be rerun before being cited for the current version.
>
> FGCS-R6 (2026-11-25): the READY_BATCH_MINMIN / READY_BATCH_MAXMIN / READY_BATCH_MCT rows also predate
> the machine-ready-time late-binding semantics (Braun et al. 2001; Maheswaran et al. 1999). This
> heterogeneous fleet (500-2000 MIPS) is exactly where a Job may now wait for a busy faster VM, so those
> rows do not describe the current heuristics.

## Setup and scale construction

The 1000-task inputs are the real Montage, CyberShake and Inspiral DAX workflows. No 5000-task DAX is
bundled, so each 1000-task DAG was expanded into **five independent, namespace-isolated copies**,
producing exactly 5000 logical tasks. The 5000 case is a reproducible scale experiment, not a claim of
a new real-world trace or of statistical equivalence to the original corpus.

All 36 runs use SHARED storage, four SPACE_SHARED VMs (500/1000/1500/2000 MIPS), `CostModel.VM`,
`runtimeReferenceMips=1000`, `randomSeed=7`, and an explicit event-recorder cap of 200000. Every
logical task completed. Algorithms are the same six makespan baselines used in the smaller study.

## Montage

| Scale | Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization |
|---:|---|---:|---:|---:|---:|---:|
| 1000 | **SHARED_STORAGE_HEFT** | **2772.66** | 701.45 | 64.76 | 0.3607 | 0.916 |
| 1000 | READY_BATCH_MAXMIN | 2790.49 | 698.22 | 64.28 | 0.3584 | 0.913 |
| 1000 | READY_BATCH_MINMIN | 2793.42 | **172.28** | **15.97** | 0.3580 | 0.912 |
| 1000 | READY_BATCH_MCT | 2795.12 | 698.65 | 64.21 | 0.3578 | 0.912 |
| 1000 | READY_BATCH_ROUNDROBIN | 3081.31 | 698.66 | 64.21 | 0.3245 | 0.850 |
| 1000 | FCFS | 3291.40 | 698.75 | 64.23 | 0.3038 | 0.812 |
| 5000 | **SHARED_STORAGE_HEFT** | **12889.65** | 3544.70 | 323.22 | 0.3879 | 0.995 |
| 5000 | READY_BATCH_MAXMIN | 12891.12 | 3526.14 | 321.37 | 0.3879 | 0.997 |
| 5000 | READY_BATCH_MINMIN | 12899.92 | **776.33** | **70.21** | 0.3876 | 0.996 |
| 5000 | READY_BATCH_MCT | 12945.01 | 3716.77 | 338.00 | 0.3862 | 0.994 |
| 5000 | READY_BATCH_ROUNDROBIN | 13012.93 | 3716.77 | 338.00 | 0.3842 | 0.990 |
| 5000 | FCFS | 13015.49 | 3721.04 | 338.37 | 0.3842 | 0.990 |

## CyberShake

| Scale | Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization |
|---:|---|---:|---:|---:|---:|---:|
| 1000 | **SHARED_STORAGE_HEFT** | **12166.88** | 4589.85 | 248.92 | 0.0822 | 0.999 |
| 1000 | FCFS | 12167.55 | 4574.87 | 254.95 | 0.0822 | 1.000 |
| 1000 | READY_BATCH_MCT | 12182.00 | 4577.30 | 261.47 | 0.0821 | 1.000 |
| 1000 | READY_BATCH_MAXMIN | 12186.73 | 4612.56 | 253.63 | 0.0821 | 1.000 |
| 1000 | READY_BATCH_ROUNDROBIN | 12197.36 | 4582.90 | 260.36 | 0.0820 | 1.000 |
| 1000 | READY_BATCH_MINMIN | 12214.78 | **2121.43** | **30.00** | 0.0819 | 0.997 |
| 5000 | **SHARED_STORAGE_HEFT** | **60894.72** | 25714.90 | 1278.85 | 0.0821 | 1.000 |
| 5000 | READY_BATCH_MAXMIN | 60911.35 | 26026.68 | 1319.52 | 0.0821 | 1.000 |
| 5000 | FCFS | 60913.14 | 25953.61 | 1378.18 | 0.0821 | 1.000 |
| 5000 | READY_BATCH_ROUNDROBIN | 60920.49 | 25943.24 | 1375.53 | 0.0821 | 1.000 |
| 5000 | READY_BATCH_MCT | 60960.63 | 25963.07 | 1376.42 | 0.0820 | 1.000 |
| 5000 | READY_BATCH_MINMIN | 61844.97 | **2306.69** | **30.52** | 0.0808 | 0.988 |

## Inspiral

| Scale | Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization |
|---:|---|---:|---:|---:|---:|---:|
| 1000 | **SHARED_STORAGE_HEFT** | **46091.30** | 6174.55 | 404.75 | 0.0217 | 0.999 |
| 1000 | READY_BATCH_MCT | 46267.77 | 5499.71 | 78.82 | 0.0216 | 0.998 |
| 1000 | FCFS | 46286.14 | 5389.87 | 67.12 | 0.0216 | 0.997 |
| 1000 | READY_BATCH_ROUNDROBIN | 46457.93 | 5493.40 | 77.45 | 0.0215 | 0.994 |
| 1000 | READY_BATCH_MAXMIN | 46675.04 | 4515.49 | 363.00 | 0.0214 | 0.991 |
| 1000 | READY_BATCH_MINMIN | 47070.83 | **3158.13** | **19.08** | 0.0212 | 0.984 |
| 5000 | **SHARED_STORAGE_HEFT** | **230414.41** | 30885.48 | 2170.12 | 0.0217 | 1.000 |
| 5000 | READY_BATCH_MINMIN | 230930.77 | **14934.50** | **79.07** | 0.0217 | 0.999 |
| 5000 | READY_BATCH_MAXMIN | 231116.44 | 22301.43 | 1792.53 | 0.0216 | 0.999 |
| 5000 | FCFS | 231145.66 | 30266.64 | 387.86 | 0.0216 | 0.998 |
| 5000 | READY_BATCH_MCT | 231145.92 | 30247.83 | 407.84 | 0.0216 | 0.998 |
| 5000 | READY_BATCH_ROUNDROBIN | 231167.50 | 30289.90 | 408.44 | 0.0216 | 0.998 |

## Interpretation

- HEFT has the lowest makespan on all three datasets at both scales. Max-Min is very close on Montage
  5000, while MCT is close on CyberShake 1000, but neither beats HEFT here.
- Min-Min again minimizes mean waiting time, but not makespan. This is consistent with short-job-first
  behavior rather than critical-path optimization. At large scale the wait and slowdown differences
  become much more visible than in the small study.
- Throughput is approximately stable when moving from 1000 to 5000 tasks: Montage HEFT 0.3607 to
  0.3879 jobs/s, CyberShake 0.0822 to 0.0821, and Inspiral 0.0217 to 0.0217. Makespan therefore grows
  primarily with work volume rather than a collapse in scheduling throughput.
- Energy is approximately five times the 1000-task value: Montage 1.303 MJ to 6.427 MJ, CyberShake
  6.080 MJ to 30.443 MJ, and Inspiral 23.031 MJ to 115.206 MJ. Cost shows the same broad scale trend.
- The 5000 case uses five independent copies, so this validates scale behavior of the simulator and
  algorithm families, not a larger real cluster or a larger original trace.

## Reproduction

`MakespanScaleComparisonTest` executes all 36 runs and checks completion and metric invariants.
`MakespanScaleComparison.main` writes the full result JSON. The generated output is
`output/makespan-scale/results.json`.
