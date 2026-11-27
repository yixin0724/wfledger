# Study Protocols

This directory holds study protocols and protocol data. Drivers and tests live in the experiments
module's standard source directories; general run operations are described in the
[Workbench guide](<../../docs/getting-started/WORKBENCH.md>) and the
[campaign guide](<../../docs/experiments/CAMPAIGNS.md>).

| Entry | Content |
|---|---|
| [standard/PROTOCOL.md](<standard/PROTOCOL.md>) | `standard-study-v1`: the standard design for new same-layer comparisons |
| [standard-workloads/](<standard-workloads/README.md>) | Specification and generator of the WfGen synthetic corpus in `datasets/wfgen-standard/` |

Network-study protocol identities (`network-limited-r10-*`, `peft-comparison-r12-*`,
`sensitivity-response-r13-*`) are registered in code by `NetworkStudyPlan` and described in the
[campaign guide](<../../docs/experiments/CAMPAIGNS.md#protocol-identities>). Their retained
evidence lives only in local `output/*.tar.gz` archives.

## Requirements for new studies

- Declare, before execution, the research question, the same-decision-layer algorithm set, input
  identities, platform and model, seed design, metrics, statistical unit, stopping conditions, and
  retention rules in one protocol document.
- Inputs are explicit and outputs go to a new directory. Retain failures and exclusion reasons; do
  not select inputs after observing outcomes. A shared root seed is not event-keyed common random
  numbers.
- Hand over the protocol, the index, every referenced bundle, and the generated inputs; a summary
  table alone is not a handover. Retained evidence, archives, fixtures, and golden values are never
  edited or deleted by documentation work.
- Aggregate random seeds within a DAG first, then compare across declared sources and conditions;
  repeated conditions are not independent samples, and non-significance does not imply
  equivalence. Independent checking tools are described in the
  [study audit notes](<../../scripts/STUDY_AUDIT.md>).
