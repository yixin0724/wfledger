# WfLedger Notice

WfLedger is a discrete-event simulator for scientific workflow scheduling research. It treats execution evidence as a first-class result and is independently developed around explicit execution contracts, independent validation, and exact replay.

Copyright 2026 WfLedger contributors.

WfLedger is distributed under the GNU General Public License, version 3 or (at your option) any later version. The license text is provided in `LICENSE` and `licenses/GPL-3.0.txt`.

## WorkflowSim provenance

WfLedger originated from WorkflowSim and retains modified WorkflowSim-derived source files. The reference upstream is:

- Project: WorkflowSim 1.0
- Repository: https://github.com/WorkflowSim/WorkflowSim-1.0
- Commit: `d3ea21afd8ce6479bd292d3bd7469045d7a36089`

Upstream source files retain their original copyright and license notices, including:

- Copyright 2012-2013 University of Southern California.
- Apache License, Version 2.0 notices in the corresponding source files.

The upstream distribution also contains `license-workflowsim.txt`, which names the Globus Toolkit Public License (GTPL) and includes a University of Southern California copyright notice. That original notice is retained verbatim in `licenses/WorkflowSim-GTPL-notice.txt`. A reference copy of the publicly documented GTPL version 3 text is supplied in `licenses/GTPL-3.0.txt`.

The upstream notice does not specify a complete per-file GTPL scope. The notice is therefore preserved without claiming that all WorkflowSim or WfLedger code is GTPL-covered. The source audit and its limits are recorded in `THIRD_PARTY.md` and `licenses/provenance.json`.

## CloudSim provenance

WfLedger includes modified CloudSim 3 source files under `simulator/src/main/java/org/cloudbus/`. Original CloudSim copyright and license notices are retained, including:

- Copyright (c) 2009-2012, The University of Melbourne, Australia.
- The original GPL references in the corresponding source files.

The license text bundled with the pinned upstream distribution is retained in `licenses/CloudSim-LGPL-3.0.txt`. The precise version-specific provenance is recorded in `THIRD_PARTY.md`; the generic word “GPL” in a source header is not used as a substitute for that record.

## Modifications

WfLedger contributors have modified and extended the upstream code, including execution contracts, dataflow and storage lifecycles, scheduling and planning adapters, energy and carbon accounting, evidence production, independent validation, experiment drivers, and user-facing reports.

The modifications are dated 2026. Original upstream copyright notices are preserved. WfLedger and its contributors do not claim that the University of Southern California, the University of Melbourne, or the upstream WorkflowSim/CloudSim projects endorse this modified platform.

The `org.workflowsim` Java namespace, Maven coordinates, and repository URLs are compatibility identifiers. They do not change the upstream copyright ownership or make WfLedger an upstream release.

## License texts and source records

- `LICENSE`: WfLedger project license.
- `THIRD_PARTY.md`: component, provenance, and modification ledger.
- `licenses/README.md`: guide to the bundled license texts.
- `licenses/provenance.json`: source URLs, audit counts, and SHA-256 hashes for the retained texts.

This notice documents provenance and licensing. It is not a legal opinion or a warranty of legal clearance. New upstream imports require a fresh per-file license review; datasets, build dependencies, LaTeX templates, and external artifacts have separate license scopes.
