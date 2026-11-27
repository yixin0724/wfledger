# Third-Party Provenance and Licensing

This document records the source, license evidence, and modification boundaries for WfLedger. It supplements `NOTICE.md`, the root `LICENSE`, and `licenses/provenance.json`; it does not replace any upstream license or copyright notice.

## Project license

WfLedger is distributed under **GPL-3.0-or-later**. The complete license is in `LICENSE` and `licenses/GPL-3.0.txt`.

The GPLv3 path avoids presenting the combined work as GPL-2.0-only. Project-owned material previously marked GPL-2.0-or-later permits that upgrade. Any file with a different license must retain its own terms; this document does not relicense upstream code.

## Source ledger

| Component | Source and scope | Original license evidence | WfLedger treatment |
|---|---|---|---|
| WorkflowSim-derived Java sources | WorkflowSim 1.0, commit `d3ea21afd8ce6479bd292d3bd7469045d7a36089`; files under `simulator/src/main/java/org/workflowsim/` with upstream notices | Apache-2.0 headers; USC copyright notices | Original notices retained; execution, scheduling, dataflow, energy, and evidence extensions marked as WfLedger modifications in `NOTICE.md` |
| CloudSim-derived Java sources | CloudSim 3 code under `simulator/src/main/java/org/cloudbus/` | Original University of Melbourne copyright and GPL references; `licenses/CloudSim-LGPL-3.0.txt` from the pinned distribution | Original notices retained; modifications and new execution/evidence code documented in `NOTICE.md` |
| Upstream GTPL notice | `license-workflowsim.txt` in the pinned WorkflowSim distribution | GTPL reference and USC copyright | Preserved verbatim as `licenses/WorkflowSim-GTPL-notice.txt`; no complete per-file scope is inferred |
| GTPL reference text | Publicly documented GTPL version 3 | Apache-2.0 terms with an explicit U.S. Government disclaimer and limitation of liability | Reference copy in `licenses/GTPL-3.0.txt`; retained for any applicable material without asserting universal coverage |
| WfLedger additions | New scheduler, model, validator, experiment, Workbench, documentation, and test code | WfLedger contributors | GPL-3.0-or-later; new copyright and modification statements are recorded in `NOTICE.md` |

## Pinned upstream evidence

- Repository: https://github.com/WorkflowSim/WorkflowSim-1.0
- Commit: `d3ea21afd8ce6479bd292d3bd7469045d7a36089`
- Source archive URL: https://codeload.github.com/WorkflowSim/WorkflowSim-1.0/zip/d3ea21afd8ce6479bd292d3bd7469045d7a36089

The archive digest is pinned in [provenance.json](<licenses/provenance.json>). The upstream archive is used for provenance. It is not bundled as a third-party source tree in the WfLedger repository.

## Audit findings

The reviewed upstream tree contains 193 Java files. Of these, 59 carry Apache-2.0 headers. No Java file in that tree contains a GTPL header. The separate upstream `license-workflowsim.txt` file is nevertheless retained because it is part of the distribution's legal notices and explicitly requires preservation of the GTPL notice.

The source audit does not prove that every historical upstream file has only one license, nor does it prove that GTPL is irrelevant to every downstream file. It establishes which notices were found and preserved. If new upstream files are imported, their license evidence must be reviewed again rather than inherited from this table.

## License texts

| File | Purpose |
|---|---|
| `licenses/Apache-2.0.txt` | Apache License, Version 2.0 text for WorkflowSim source headers and other permissive components |
| `licenses/CloudSim-LGPL-3.0.txt` | LGPL-3.0 text bundled with the pinned upstream distribution |
| `licenses/WorkflowSim-GTPL-notice.txt` | Verbatim upstream GTPL/USC notice |
| `licenses/GTPL-3.0.txt` | GTPL version 3 reference text |
| `licenses/GPL-3.0.txt` | WfLedger project license |
| `licenses/provenance.json` | Source URLs, hashes, counts, and review boundaries |

## Modification and naming boundaries

WfLedger is a new product name, not an upstream WorkflowSim release. The name change does not remove or alter upstream copyright. The `org.workflowsim` Java namespace and Maven coordinates are retained as compatibility identifiers, not as a claim of upstream authorship or endorsement.

Modifications include execution contracts, scheduling and planning changes, coherent file/storage lifecycles, failure and retry handling, energy/carbon/DVFS accounting, evidence generation, independent validators, experiment drivers, and offline reports. These modifications are dated 2026 and are described in `NOTICE.md`.

## Other license scopes

This ledger does not assign a single license to datasets, Maven or Python dependencies, browser tooling, LaTeX templates, generated experiment data, or external artifact repositories. Those scopes require separate review before distribution. Generated `paper/` material is local and ignored; it is not a substitute for the source license notices.

This is an engineering provenance record, not a legal opinion or a warranty of legal clearance. If a distribution has a different bundle or includes additional dependencies, its third-party manifest must be regenerated and reviewed.
