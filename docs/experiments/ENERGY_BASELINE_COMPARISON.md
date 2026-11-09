# Energy and EDP Baseline Comparison (2026-11-09)

## Experimental setup

Five real scientific workflows were run: Montage 50/100, CyberShake 50, SIPHT 60 and Inspiral 50. Each uses four SPACE_SHARED VMs with declared power and two execution models: a flat-power model for HEFT/GreenHEFT/MOHEFT and a tiered model for HEFT_DEFAULT, MIN_POWER, EDP_HEURISTIC and DVFS_SLACK. All logical tasks completed.

Flat and tiered groups are reported separately and are not treated as one shared population. MIN_POWER always uses the lowest tier; EDP_HEURISTIC chooses a tier by the local EDP estimate; DVFS_SLACK accepts a lower tier only when its deadline and total-energy gates pass. All runs use SHARED storage, `CostModel.VM`, `runtimeReferenceMips=1000`, `randomSeed=7`, and an event-recorder cap of 200000.

Energy includes every declared VM for the full makespan, including idle time. EDP is total energy multiplied by makespan. Mean waiting is ready-to-start waiting. Energy saving and makespan change are relative to the corresponding HEFT baseline in the same power group.

## datasets/dax/cybershake/n50/CyberShake_50.dax

| Group | Algorithm | Makespan (s) | Energy (J) | EDP (J·s) | Mean wait (s) | VM utilization | Energy saving | Makespan change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| FLAT | SHARED_STORAGE_HEFT | 3198.47 | 1591539.16 | 5090493180.2 | 140.58 | 0.9934 | +0.00% | +0.00% |
| FLAT | GREEN_HEFT | 5849.23 | 2078748.00 | 12159076554.7 | 1031.91 | 0.5355 | -30.61% | +82.88% |
| FLAT | MO_HEFT | 5885.68 | 2082685.55 | 12258028651.9 | 1033.72 | 0.5322 | -30.86% | +84.02% |
| TIERED | HEFT_DEFAULT | 3198.47 | 1591539.16 | 5090493180.2 | 140.58 | 0.9934 | +0.00% | +0.00% |
| TIERED | HEFT_MIN_POWER | 3616.92 | 355167.86 | 1284615434.8 | 250.09 | 0.9715 | +77.68% | +13.08% |
| TIERED | HEFT_EDP_HEURISTIC | 3318.61 | 493482.39 | 1637675753.2 | 158.14 | 0.9894 | +68.99% | +3.76% |
| TIERED | DVFS_SLACK | 3482.04 | 371656.06 | 1294122553.5 | 211.40 | 0.9896 | +76.65% | +8.87% |

## datasets/dax/inspiral/n50/Inspiral_50.dax

| Group | Algorithm | Makespan (s) | Energy (J) | EDP (J·s) | Mean wait (s) | VM utilization | Energy saving | Makespan change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| FLAT | SHARED_STORAGE_HEFT | 2543.69 | 1198898.46 | 3049628030.4 | 235.13 | 0.9030 | +0.00% | +0.00% |
| FLAT | GREEN_HEFT | 2543.69 | 1198898.46 | 3049628030.4 | 235.13 | 0.9030 | +0.00% | +0.00% |
| FLAT | MO_HEFT | 2541.03 | 1199008.47 | 3046716694.5 | 234.86 | 0.9046 | -0.01% | -0.10% |
| TIERED | HEFT_DEFAULT | 2543.69 | 1198898.46 | 3049628030.4 | 235.13 | 0.9030 | +0.00% | +0.00% |
| TIERED | HEFT_MIN_POWER | 5052.46 | 489330.78 | 2472325269.8 | 467.69 | 0.9042 | +59.18% | +98.63% |
| TIERED | HEFT_EDP_HEURISTIC | 3290.52 | 1063587.79 | 3499751939.6 | 302.22 | 0.8742 | +11.29% | +29.36% |
| TIERED | DVFS_SLACK | 2997.53 | 1068379.62 | 3202495508.3 | 302.69 | 0.9047 | +10.89% | +17.84% |

## datasets/dax/montage/n100/Montage_100.dax

| Group | Algorithm | Makespan (s) | Energy (J) | EDP (J·s) | Mean wait (s) | VM utilization | Energy saving | Makespan change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| FLAT | SHARED_STORAGE_HEFT | 278.14 | 124996.16 | 34766703.7 | 56.10 | 0.8487 | +0.00% | +0.00% |
| FLAT | GREEN_HEFT | 279.88 | 125233.21 | 35050290.5 | 55.70 | 0.8295 | -0.19% | +0.62% |
| FLAT | MO_HEFT | 279.88 | 125230.51 | 35049534.8 | 55.69 | 0.8295 | -0.19% | +0.62% |
| TIERED | HEFT_DEFAULT | 278.14 | 124996.16 | 34766703.7 | 56.10 | 0.8487 | +0.00% | +0.00% |
| TIERED | HEFT_MIN_POWER | 519.12 | 49103.47 | 25490722.6 | 105.89 | 0.8624 | +60.72% | +86.64% |
| TIERED | HEFT_EDP_HEURISTIC | 287.76 | 121532.21 | 34972636.3 | 56.84 | 0.8699 | +2.77% | +3.46% |
| TIERED | DVFS_SLACK | 299.95 | 117203.13 | 35154630.6 | 59.82 | 0.8698 | +6.23% | +7.84% |

## datasets/dax/montage/n50/Montage_50.dax

| Group | Algorithm | Makespan (s) | Energy (J) | EDP (J·s) | Mean wait (s) | VM utilization | Energy saving | Makespan change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| FLAT | SHARED_STORAGE_HEFT | 139.22 | 59898.58 | 8338900.6 | 18.12 | 0.8262 | +0.00% | +0.00% |
| FLAT | GREEN_HEFT | 143.34 | 60245.64 | 8635439.5 | 18.47 | 0.7750 | -0.58% | +2.96% |
| FLAT | MO_HEFT | 143.34 | 60245.64 | 8635439.5 | 18.47 | 0.7750 | -0.58% | +2.96% |
| TIERED | HEFT_DEFAULT | 139.22 | 59898.58 | 8338900.6 | 18.12 | 0.8262 | -0.00% | +0.00% |
| TIERED | HEFT_MIN_POWER | 260.02 | 23977.31 | 6234648.3 | 34.30 | 0.8379 | +59.97% | +86.78% |
| TIERED | HEFT_EDP_HEURISTIC | 165.56 | 57320.09 | 9489857.3 | 22.76 | 0.7788 | +4.30% | +18.92% |
| TIERED | DVFS_SLACK | 150.09 | 55126.52 | 8274205.8 | 21.71 | 0.8405 | +7.97% | +7.81% |

## datasets/dax/sipht/n60/Sipht_60.dax

| Group | Algorithm | Makespan (s) | Energy (J) | EDP (J·s) | Mean wait (s) | VM utilization | Energy saving | Makespan change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| FLAT | SHARED_STORAGE_HEFT | 2990.26 | 1212699.91 | 3626282765.4 | 234.39 | 0.6516 | +0.00% | +0.00% |
| FLAT | GREEN_HEFT | 2990.26 | 1212699.91 | 3626282765.4 | 234.40 | 0.6516 | +0.00% | +0.00% |
| FLAT | MO_HEFT | 2990.26 | 1212627.63 | 3626066841.8 | 239.17 | 0.6576 | +0.01% | +0.00% |
| TIERED | HEFT_DEFAULT | 2990.26 | 1212699.91 | 3626282765.4 | 234.39 | 0.6516 | -0.00% | +0.00% |
| TIERED | HEFT_MIN_POWER | 5941.63 | 531911.59 | 3160421865.4 | 463.05 | 0.6519 | +56.14% | +98.70% |
| TIERED | HEFT_EDP_HEURISTIC | 4717.82 | 808092.68 | 3812433914.1 | 317.54 | 0.6668 | +33.36% | +57.77% |
| TIERED | DVFS_SLACK | 3142.04 | 1163353.85 | 3655305289.8 | 436.95 | 0.6817 | +4.07% | +5.08% |

## Interpretation

- MIN_POWER gives the largest energy reduction, but usually costs roughly twice the makespan. It is an energy/EDP tradeoff, not a free improvement.
- DVFS_SLACK generally reduces energy with a smaller makespan penalty than MIN_POWER. Its EDP is usually better than HEFT_DEFAULT, but not on every dataset; deadline and fleet idle energy can make a downgrade counterproductive.
- GreenHEFT optimizes its compute-energy objective, not total fleet energy. On CyberShake, Montage and SIPHT it can use slower resources and increase total energy because idle energy accumulates over a longer makespan. MOHEFT sometimes improves the tradeoff slightly, but is not guaranteed to dominate HEFT.
- EDP_HEURISTIC does not mean minimum energy. It may select a faster tier to limit delay, so its energy can be higher than MIN_POWER and occasionally higher than HEFT_DEFAULT.
- The results demonstrate the energy/makespan tradeoff, not universal superiority. A different tier table, storage rate, VM count or DAG can change which policy is best.

## Reproduction

`EnergyBaselineComparisonTest` executes all 35 runs and checks completion and metric invariants. `EnergyBaselineComparison.main` writes the full result JSON to `output/energy-baseline/results.json`.
