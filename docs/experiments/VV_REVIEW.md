# Independent V&V Review Record

## Review identity and disposition

| Field | Record |
|---|---|
| `reviewer` | Independent structured technical reviewer that did not implement the platform or derive its expected values |
| `reviewType` | Structured independent technical walkthrough; **not a human independent signoff** |
| `materials` | Current contracts, case/golden ledgers, source and tests, dataset indexes, ignore rules, CI configuration, clean-archive and extended-suite verification receipts |
| `disposition` | `APPROVED` only for the declared internal-validity scope and the sampled technical review domain |
| `releaseBlockers` | None remaining after VV-NEW-01 was closed |
| `realPlatformValidation` | `NOT_ESTABLISHED`; no production-cluster fidelity, real-network prediction, or universal algorithm ranking is approved |
| `humanApproval` | Not claimed and not implied by this record |

## Findings and disposition

### VV-NEW-01 — clean-checkout corpus dependency

**Initial severity:** high / release-blocking.

The original 17-instance Montage suite required 16 files that are present only in the optional local
snapshot and ignored by Git. A clean checkout contained only one file, so the default Maven gate
would fail. The case ledger also risked treating that optional snapshot as a standard-checkout
capability.

**Disposition:** closed.

- `e98fb73` separated the standard one-input suite from the opt-in 17-instance suite and moved
  shared verification into one support class.
- `7b14d2a` removed extended-corpus enumeration from the default contract test. Default Surefire
  excludes `*ExtendedCorpusTest`; only the explicit `extended-corpus` profile enables it.
- `make extended-corpus` requires the full snapshot and index rather than silently skipping missing
  inputs.
- `WF-APPS-STD-001` and `WF-APPS-EXT-001` now distinguish standard and extended validation points.
- A fresh `git archive HEAD` contained exactly one Montage JSON and its standard tests passed. The
  extended Surefire report independently recorded 17 tests, zero failures, and zero errors.

The original independent reviewer confirmed closure as `APPROVED` within this technical scope,
while noting that the fix receipts, not a fresh full-suite execution by that reviewer, were the
basis of the decision.

## CAPABILITY assessment

| Dimension | Assessment |
|---|---|
| Data pedigree | Supported within the declared standard and extended input populations. Inputs remain separated by source and execution domain |
| Code verification | Supported for the sampled CloudSim/WorkflowSim, tied-rank statistics, campaign recompute, WfFormat, and profile checks |
| Solution verification / validation | Structural, contract, and fixed-reference verification only; no production-platform operational validation |
| Oracle legality | Paper, contract, invariant, fixed-reference implementation, and labeled regression classes remain distinct |
| Development review/process | Supported after the corpus dependency and default-profile boundaries were closed and independently re-reviewed |

## RESULTS assessment

| Dimension | Assessment |
|---|---|
| Intended use | Internal validity and reproducible, traceable evidence only; no real-cluster or optimality claim |
| Input/results pedigree | Standard and extended results may be cited only with their input sets and execution entry points; never as an unconditional built-in 17/17 claim |
| Statistical boundaries | Exact tied-rank Wilcoxon is limited to its declared domain; approximation, small samples, non-event-keyed CRN, ties, and Holm limitations remain visible |
| Evidence recomputation | Campaign aggregation is rebuilt from validated manifests but shares statistical primitives; Python's shared core is independent of Java, not independent per auditor |
| Robustness/performance | Fingerprint equality and profile comparison do not establish universal speedup, timing thresholds, or cross-environment performance claims |
| Frozen results | Historical bundles support their registered evidence contracts, not current regeneration of every protocol or real-world validity |

## Accepted residual boundaries

1. `REAL-PLATFORM-001` remains `NOT_ESTABLISHED`. Structural input verification and agreement with
   another simulator are not empirical validation.
2. This review is not human approval, legal certification, or NASA/ASME compliance certification.
3. Registered mutants are semantic canaries, not exhaustive correctness proof. Declaration-only
   fields and historical protocol boundaries remain visible.
4. Golden regressions detect drift only. Source-tree hashes are not release signatures, and frozen
   evidence is not a current protocol-regeneration guarantee.
5. Any semantic, input, referent, platform, or metric change reopens the affected cases; this
   disposition is not a permanent waiver for future changes.

## Supporting records

- [V&V case and golden-evidence ledger](<VV_CASE_LEDGER.md>)
- [Golden and reference-evidence registry](<GOLDEN_EVIDENCE_REGISTRY.md>)
- [Application structural benchmarks](<APPLICATION_BENCHMARKS.md>)
- [Independent V&V review protocol](<VV_REVIEW_PROTOCOL.md>)
