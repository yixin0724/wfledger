# Independent V&V Review Protocol

This protocol defines the independent technical review required before a research claim is
released. It does not assert that such a review has already occurred. The current status is
**PENDING_INDEPENDENT_REVIEW**: the design and implementation are self-reviewed, but no independent
reviewer has signed a capability/results assessment.

## Reviewer and independence

The reviewer must not be the author of the implementation or of the expected-value derivation under
review. Record the reviewer, date, materials, conflicts of interest, and whether the reviewer had
access to the original inputs, source, contracts, test output, and retained evidence. A green build
or an AI-generated summary is not a substitute for this record.

## Structured walkthrough

For each V&V case, the reviewer must answer and cite evidence for:

1. What is the intended use, decision, output metric, and consequence of a wrong result?
2. Are the conceptual model, algorithm, data pedigree, assumptions, and excluded capabilities clear?
3. Is the expected value derived from a legal source, independently of the production function?
4. Are code verification, solution verification, and operational validation kept distinct?
5. Do the referent, validation point, metric, threshold, and uncertainty/sampling boundaries match?
6. Are random streams, replication, pairing, ties, multiplicity, and small-sample limitations stated?
7. Are configuration, evidence hashes, rerun boundaries, and independent recomputation complete?
8. Are performance comparisons semantically equivalent and environmentally identified?
9. Are declaration-only fields, unexamined domains, defects, and warnings visible to users?
10. Does any statement overreach the verified domain, especially real-platform or optimality claims?

## Decision record

| Field | Required content |
|---|---|
| `caseId` | Case and protocol identity |
| `reviewer` | Independent reviewer and independence rationale |
| `materials` | Source, contract, input, referent, test, and evidence versions |
| `findings` | Accepted issue, rejected issue, required change, or no issue, with evidence |
| `capabilityAssessment` | Data pedigree, verification, validation, development review/process, separately |
| `resultsAssessment` | Use/input/uncertainty/robustness/review/process, separately |
| `residualRisk` | Unexamined domain, warnings, accepted risk, and approval |
| `disposition` | `APPROVED`, `CHANGES_REQUIRED`, `REJECTED`, or `PENDING_INDEPENDENT_REVIEW` |

No aggregate credibility score is permitted. A critical `CHANGES_REQUIRED` or `REJECTED` item
blocks the associated claim even if every other item passes. After any semantic, input, referent,
platform, or metric change, the affected cases must be reviewed again.

See [V&V case and golden-evidence ledger](<VV_CASE_LEDGER.md>) and
[golden and reference-evidence registry](<GOLDEN_EVIDENCE_REGISTRY.md>).
