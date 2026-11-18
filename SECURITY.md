# Security and Data Handling

## Reporting a vulnerability

Do not open a public issue for an unpatched vulnerability, exposed credential, or sensitive dataset. Use GitHub private vulnerability reporting when it is enabled for this repository; otherwise contact the maintainers through an established private maintainer channel. Do not include secrets, private workflow inputs, or unnecessary personal information in the report.

Include the affected component, a minimal reproduction, the security impact, and any suggested mitigation. Do not test against other users' systems or upload repository data to third-party services.

## Data handled by the simulator

Simulation inputs and generated artifacts can contain:

- workflow structure, task names, logical filenames, and file sizes;
- input SHA-256 hashes, platform declarations, scheduling decisions, and runtime metrics;
- absolute working-directory and code-source paths from the machine that produced the run;
- optional failure, network, file-lifecycle, energy, and cost details.

These artifacts are evidence, not anonymized telemetry. Review them before sharing or committing them. The simulator does not provide a general-purpose anonymization or secret-redaction service.

## Local development precautions

- Keep credentials in an untracked secret manager or environment-specific configuration, never in source files, experiment configurations, or test fixtures.
- Do not run private or unpublished workflows through public CI or share their manifests without reviewing the paths, names, hashes, and metadata they disclose.
- Keep generated results under `output/` or another untracked local directory. Generated CI evidence and new manifest, metrics, and event files are ignored by default and must not be committed. `make quality-gate` runs `scripts/check-upload-safety.py` to reject runtime output, credentials, machine-local paths, and oversized files.
- Review local outputs before publishing an archive. `make clean-output` removes only the documented regenerable output families, not every possible local experiment directory.
- Do not use `git add -f` for ignored outputs or credentials without reviewing the exact files and their contents.
- If a credential is committed, revoke and rotate it immediately. Removing it from the latest source does not remove it from Git history or public mirrors; history cleanup requires a separate coordinated action.

## Runtime and report boundaries

The maintained simulator does not require a network service or telemetry endpoint. Offline HTML reports are checked for unexpected external requests and secondary-file reads. Maven, npm, and other package managers may access their configured repositories to download declared dependencies; dependency downloads are not evidence uploads.

The project uses simulated resources and declared models. It does not need production cloud credentials, real cluster access, or cloud-provider billing data for its standard experiments.
