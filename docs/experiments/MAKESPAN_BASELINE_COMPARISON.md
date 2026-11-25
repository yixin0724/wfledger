# Traditional Makespan Baseline Comparison (2026-11-09)

> Recorded before FGCS-R3 (2026-11-24). Online rows use the former read-free estimate and
> event-order-dependent rounds; they must be rerun before being cited for the current version.
>
> FGCS-R6 (2026-11-25): the READY_BATCH_MINMIN / READY_BATCH_MAXMIN / READY_BATCH_MCT rows also predate
> the machine-ready-time late-binding semantics (Braun et al. 2001; Maheswaran et al. 1999). This
> heterogeneous fleet (500-2000 MIPS) is exactly where a Job may now wait for a busy faster VM, so those
> rows do not describe the current heuristics.

## Experimental setup

Seven real Pegasus DAX workflows were run through `SimulationRunner`: Montage 50/100,
CyberShake 50/100, SIPHT 60, Inspiral 50 and Epigenomics 46. Every run used the same SHARED storage
model, four SPACE_SHARED VMs (500/1000/1500/2000 MIPS), `CostModel.VM`,
`runtimeReferenceMips=1000`, and `randomSeed=7`. All logical tasks completed.

Algorithms: offline SHARED_STORAGE_HEFT, online READY_BATCH_MINMIN, READY_BATCH_MAXMIN,
READY_BATCH_MCT, FCFS and READY_BATCH_ROUNDROBIN. Static independent-task `STATIC_MINMIN` was not
used because it rejects DAGs with dependencies. All algorithms ran under the same physical model;
this is not a cross-model or real-cluster calibration.

Metrics: makespan is the simulation end clock; mean waiting is ready-to-start waiting; true slowdown
is `max((wait + execution) / max(execution, 10), 1)` per compute Job, then averaged. Throughput is
successful compute Jobs per simulated second. Energy and modeled processing cost are secondary
observations, not optimization targets. The full JSON also includes ready-to-decision and
 decision-to-start delays, mean run/response time, busy intervals, and utilization.

## Montage 50

| Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization | Energy (J) | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| **SHARED_STORAGE_HEFT** | **139.22** | 18.12 | 2.53 | 0.3592 | 0.826 | 59898.58 | 1811.17 |
| READY_BATCH_MCT | 140.10 | 18.85 | 2.58 | 0.3569 | 0.822 | 59969.22 | 1811.56 |
| READY_BATCH_MAXMIN | 140.79 | 18.13 | 2.53 | 0.3551 | 0.818 | 59987.42 | 1811.28 |
| READY_BATCH_MINMIN | 146.26 | **8.96** | **1.63** | 0.3418 | 0.805 | 60232.58 | 1781.22 |
| READY_BATCH_ROUNDROBIN | 154.14 | 18.90 | 2.59 | 0.3244 | 0.769 | 59516.33 | 1700.90 |
| FCFS | 171.18 | 19.16 | 2.62 | 0.2921 | 0.719 | 59671.72 | 1674.09 |

## Montage 100

| Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization | Energy (J) | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| **SHARED_STORAGE_HEFT** | **278.14** | 56.10 | 5.98 | 0.3595 | 0.849 | 124996.16 | 3800.20 |
| READY_BATCH_MCT | 288.94 | 54.01 | 5.72 | 0.3461 | 0.846 | 125437.52 | 3762.83 |
| READY_BATCH_MINMIN | 290.66 | **23.87** | **2.95** | 0.3440 | 0.842 | 125525.22 | 3762.01 |
| READY_BATCH_MAXMIN | 290.77 | 53.05 | 5.63 | 0.3439 | 0.842 | 125549.01 | 3763.56 |
| READY_BATCH_ROUNDROBIN | 318.24 | 53.65 | 5.69 | 0.3142 | 0.791 | 126029.20 | 3650.25 |
| FCFS | 344.59 | 54.02 | 5.72 | 0.2902 | 0.749 | 125743.82 | 3560.80 |

## CyberShake 50

| Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization | Energy (J) | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| **READY_BATCH_MCT** | **3186.37** | 100.77 | 5.82 | 0.0157 | 0.997 | 1587318.63 | 47546.59 |
| READY_BATCH_MAXMIN | 3194.01 | 138.33 | 8.20 | 0.0157 | 0.993 | 1585690.20 | 47492.30 |
| SHARED_STORAGE_HEFT | 3198.47 | 140.58 | 8.08 | 0.0156 | 0.993 | 1591539.16 | 47772.70 |
| FCFS | 3200.64 | 120.35 | 7.25 | 0.0156 | 0.994 | 1592334.91 | 47771.33 |
| READY_BATCH_MINMIN | 3230.81 | **49.17** | **1.97** | 0.0155 | 0.985 | 1591056.27 | 47538.93 |
| READY_BATCH_ROUNDROBIN | 3250.59 | 112.24 | 6.21 | 0.0154 | 0.982 | 1592148.38 | 47557.10 |

## CyberShake 100

| Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization | Energy (J) | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| **READY_BATCH_MAXMIN** | **6349.69** | 1127.19 | 31.13 | 0.0157 | 0.999 | 3171569.78 | 95127.12 |
| SHARED_STORAGE_HEFT | 6354.87 | 997.77 | 23.16 | 0.0157 | 0.998 | 3173070.39 | 95207.46 |
| READY_BATCH_MCT | 6381.35 | 937.79 | 26.06 | 0.0157 | 0.997 | 3178626.30 | 95250.61 |
| FCFS | 6400.08 | 917.68 | 25.54 | 0.0156 | 0.995 | 3183049.00 | 95411.50 |
| READY_BATCH_ROUNDROBIN | 6434.75 | 911.32 | 25.33 | 0.0155 | 0.989 | 3177456.13 | 95139.81 |
| READY_BATCH_MINMIN | 6464.85 | **187.46** | **2.31** | 0.0155 | 0.983 | 3179272.36 | 95430.00 |

## SIPHT 60

| Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization | Energy (J) | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| **SHARED_STORAGE_HEFT** | **2990.26** | 234.39 | 20.72 | 0.0194 | 0.652 | 1212699.91 | 38077.53 |
| READY_BATCH_MAXMIN | 3404.16 | 985.43 | 93.50 | 0.0170 | 0.677 | 1235749.31 | 37583.63 |
| FCFS | 5720.21 | 45.11 | 4.34 | 0.0101 | 0.514 | 1346924.24 | 31075.77 |
| READY_BATCH_MCT | 5879.59 | 53.15 | 5.16 | 0.0099 | 0.497 | 1354697.46 | 30600.15 |
| READY_BATCH_ROUNDROBIN | 7595.86 | 45.07 | 4.34 | 0.0076 | 0.469 | 1437703.46 | 27449.90 |
| READY_BATCH_MINMIN | 8968.08 | **8.09** | **1.09** | 0.0065 | 0.387 | 1508800.68 | 31513.13 |

## Inspiral 50

| Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization | Energy (J) | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| **SHARED_STORAGE_HEFT** | **2543.69** | 235.13 | 13.99 | 0.0197 | 0.903 | 1198898.46 | 36329.03 |
| READY_BATCH_MCT | 3143.32 | 198.59 | 1.96 | 0.0159 | 0.791 | 1228996.32 | 35672.39 |
| FCFS | 3208.94 | 196.27 | 1.97 | 0.0156 | 0.791 | 1232216.18 | 34851.01 |
| READY_BATCH_ROUNDROBIN | 3214.70 | 209.39 | 2.00 | 0.0156 | 0.773 | 1232361.39 | 35373.23 |
| READY_BATCH_MAXMIN | 3456.11 | 176.57 | 13.52 | 0.0145 | 0.743 | 1244603.84 | 34470.94 |
| READY_BATCH_MINMIN | 3773.65 | **161.73** | **1.69** | 0.0133 | 0.686 | 1260387.75 | 35347.68 |

## Epigenomics 46

| Algorithm | Makespan (s) | Mean wait (s) | True slowdown | Throughput (jobs/s) | VM utilization | Energy (J) | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| **SHARED_STORAGE_HEFT** | **9639.92** | 638.72 | 38.28 | 0.0049 | 0.860 | 4354618.14 | 132735.06 |
| READY_BATCH_MINMIN | 10013.29 | **430.39** | 4.71 | 0.0047 | 0.822 | 4375392.04 | 134029.94 |
| READY_BATCH_ROUNDROBIN | 11728.63 | 479.54 | 21.37 | 0.0040 | 0.739 | 4458542.25 | 127196.26 |
| READY_BATCH_MCT | 14815.33 | 478.64 | **1.43** | 0.0032 | 0.624 | 4611242.64 | 129804.83 |
| READY_BATCH_MAXMIN | 15574.69 | 466.60 | 38.71 | 0.0030 | 0.606 | 4645485.53 | 129189.44 |
| FCFS | 18150.45 | 481.91 | 21.36 | 0.0026 | 0.569 | 4754935.94 | 119126.71 |

## Interpretation

- HEFT has the best makespan on five of seven datasets. READY_BATCH_MCT wins CyberShake 50, while
  READY_BATCH_MAXMIN narrowly wins CyberShake 100. HEFT is not universally optimal.
- READY_BATCH_MINMIN has the lowest mean waiting time on every dataset and usually the lowest true
  slowdown, but frequently has a much worse makespan because short jobs are favored over the critical
  path. Epigenomics is the exception where MCT has the lower true slowdown.
- Throughput and utilization generally move inversely to makespan, but are not interchangeable with
  it. Energy and cost also do not strictly rank with makespan because VM mix, idle time and file
  movement differ.
- These are controlled simulation results, not proof of universal algorithm superiority. Further
  comparison should vary VM count, bandwidth, storage rate and failure configuration before making a
  research claim.

## Reproduction

`MakespanBaselineComparisonTest` executes all 42 runs and checks completion and metric invariants.
`MakespanBaselineComparison.main` writes the full result JSON. The retained output is
`output/makespan-baseline/results.json` (generated, not a hand-maintained golden).
