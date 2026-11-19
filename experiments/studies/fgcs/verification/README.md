# FGCS Adversarial Artifact-Verification Protocol

## Scope and research questions

This focused mutation study tests **semantic validation after integrity repair**, not merely stale-hash detection. For this study, the RQs are operationalized as follows:

- **RQ1 — genuine-artifact control:** do unmodified, small coherent V2/V3 bundles written by the current `SimulationRunner` and `ExperimentArtifactWriter` pass both validation paths?
- **RQ2 — adversarial detection:** which hand-derived semantic corruptions are rejected after **every** manifest artifact SHA-256 and byte size is recomputed, event/lifecycle sequences are repaired, and applicable event counters are updated?

The two paths are Java `ExperimentArtifactValidator` and the separate Python process `python3 -B scripts/verify-network-ledger.py <manifest> --json`, which dispatches to the V2/V3 lifecycle and run-context auditors. Expected outcomes are fixed from the lifecycle/manifest contracts before execution. They are not inferred from validator output. `results.json` records expected and actual verdicts, the hand-derived invariant, and the actual semantic reason for every case/validator pair.

## Controlled input and execution

The driver writes a new four-task DAX only under the generated output directory. Tasks have runtimes 10/20/30/40 seconds, outputs `f1`/`f2`/`f3`/`f4` of 1/2/3/4 MB, and edges `1 → 2 → 3 → 4`; each non-root Task consumes its parent's actual named output. This is deliberately not the generic FGCS control-only chain, whose file declarations do not exercise producer provenance.

Both runs use two VMs, LOCAL file access, RANDOM planning plus STATIC dispatch, NONE clustering, complete lifecycle capture, and shared coherent V2 or bounded-storage V3 physics. Simulations run serially through the standard runner/session boundary. No production code, event schema, manuscript source, existing FGCS driver, or frozen dataset is modified.

Run from the repository root:

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.verification.AdversarialEvidenceStudy
```

The default output is `paper/tmp/experiments/verification/results.json`; an optional first argument selects another output directory. Genuine bundles and every corrupted bundle remain beneath `paper/tmp/experiments/verification/evidence/` for independent reruns. Generated outputs are not source-controlled.

Focused tests and the existing independent-validator self-tests are:

```bash
mvn -q -pl :workflowsim-workbench -am \
  -Dtest=AdversarialEvidenceStudyTest -Dsurefire.failIfNoSpecifiedTests=false test
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test-file-lifecycle-context.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test-storage-lifecycle-context.py
```

## Mutation matrix and measured outcome

`J` is the Java artifact validator and `P` the Python validator. Arrows show protocol expectation → measured outcome. Every observation matched its hand-derived expectation in the retained run.

| Case | Hand-derived corruption and required invariant | J expected → actual | P expected → actual | Measured semantic reason |
|---|---|---|---|---|
| `v2-parent-before-child-request` | Move a child request before its parent's successful terminal event at the same timestamp; a control parent must succeed before request release | REJECT → REJECT | REJECT → REJECT | Control parent has no successful outcome / request before control-parent success |
| `v2-task-identity-mismatch` | Change only the modeled CPU `taskId`; singleton event, Job, and Task identities must agree | REJECT → REJECT | REJECT → REJECT | Mismatched Task timing / disagreement with Task result |
| `v2-source-provenance-mismatch` | Attribute an input source replica to a different Task; visible-source producer provenance must be preserved | REJECT → REJECT | REJECT → REJECT | Selected source replica/provenance differs from independent catalog replay |
| `v2-reordered-decision-dispatch` | Swap compute decision and dispatch, then repair all sequences; decision must precede dispatch | ACCEPT → ACCEPT | REJECT → REJECT | Python: main Job anchors violate sequence causality |
| `v2-missing-simulation-terminal` | Delete the terminal marker and repair count/sequences/hashes; the declared events-v1 contract requires one terminal event | REJECT → REJECT | ACCEPT → ACCEPT | Java: exactly one `SIMULATION_TERMINATED` is required |
| `v2-makespan-metric-mismatch` | Move terminal/end by +0.5 s but retain the old makespan metric in both metric copies; makespan must equal terminal end | REJECT → REJECT | ACCEPT → ACCEPT | Java: recomputed `makespanSeconds` differs |
| `v2-energy-metric-out-of-scope` | Add 1 J to total energy without changing per-VM energy or busy intervals; conservation must not be assumed without an energy auditor | ACCEPT → ACCEPT | ACCEPT → ACCEPT | Expected coverage boundary, not a valid energy certificate |
| `v3-output-settlement-missing` | Delete an output settlement, repair lifecycle sequences/count, retain COMPLETE/zero drops; final output must drain and become quiescent | REJECT → REJECT | REJECT → REJECT | Final storage lifecycle is not quiescent |
| `v3-source-provenance-mismatch` | Fabricate an input upload's producer identity; storage replay must retain the actual producer | REJECT → REJECT | REJECT → REJECT | Selected source replica/provenance differs from independent catalog replay |

The two genuine controls (`genuine-v2`, `genuine-v3`) are ACCEPT → ACCEPT on both paths. The current measured totals are:

| Measure | Count |
|---|---:|
| RQ1 genuine bundles / validator observations | 2 / 4 |
| RQ1 genuine bundles accepted by both | 2 |
| RQ2 corrupted bundles / validator observations | 9 / 18 |
| Expected and actual rejection observations | 13 / 13 |
| Expected and actual acceptance observations (explicit boundaries) | 5 / 5 |
| Corruptions rejected by both paths | 5 |
| Corruptions rejected by exactly one path | 3 |
| Corruptions rejected by at least one path | 8 |
| Unexpected outcomes / semantic-reason mismatches | 0 / 0 |

The contract basis is the ordered event, producer, CPU-causality, and complete-bundle rules in `docs/advanced/FILE_LIFECYCLE_FORMAT.md` and the output-closure rules in `docs/advanced/STORAGE_DATAFLOW_CONTRACT.md`. The tests assert both verdict and semantic reason; a stale `SHA-256 mismatch` cannot satisfy a rejection expectation.

## Coverage boundaries and non-claims

- The measured Java path does **not** reject the equal-time decision/dispatch swap; Python does. Sequence contiguity alone is not causal-order validation.
- The selected Python lifecycle path does **not** enforce the general terminal-marker contract or recompute `makespanSeconds`; Java manifest-v4 validation does.
- Neither selected path recomputes `energyMetrics`, so both accept the energy corruption. This says nothing against the separate `scripts/verify-study-evidence.py` energy-conservation auditor, which is outside this focused matrix.
- V3 output rejection covers declared SOURCE-drain/quiescence, not per-interval fluid service area, packets, real storage, or hardware behavior. V2/V3 lifecycle acceptance is not authentication against an adversary who can replace the whole evidence-generating implementation.
- Expected acceptance documents current validator coverage; it never makes corrupted evidence scientifically valid. These results must not be summarized as “every validator rejects every corruption,” nor generalized to untested schemas, event types, failure/retry modes, topologies, or physical models.

## Source layout

- `AdversarialEvidenceStudy.java`: serial baseline generation, hand-derived mutations, Java/Python execution, and main entry point.
- `AdversarialBundle.java`: bundle copying, sequence/counter repair, and complete SHA-256/size recomputation.
- `AdversarialResultsWriter.java`: compact deterministic JSON projection and measured counts.
- `AdversarialEvidenceStudyTest.java`: genuine controls, all expected outcomes, semantic reasons, coverage contrasts, and results-file round trip.
