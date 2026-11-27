# Contributing to WfLedger

Thank you for contributing. Bug reports, reproducible experiments, scheduling research, and carefully scoped platform improvements are welcome. Use the [issue tracker](https://github.com/yixin0724/wfledger/issues) to describe a defect or propose a substantial change before implementing it.

## Development setup

Requires JDK 17+, Maven 3.6.3+, and Python 3. Browser acceptance also requires the Node.js dependencies described in the [build guide](<docs/getting-started/BUILD.md>). Build all modules through the Maven reactor:

```bash
mvn -q compile
mvn -pl :workflowsim test
make quality-gate
```

Always use `-am` when running experiments-module tests directly, so Maven rebuilds the simulator instead of resolving an older installed JAR.

## Recommended workflow

1. Start a focused branch from `main` and keep each change scoped.
2. For a behavior change, add a failing regression test first. Derive its expected result from a primary paper, an explicit contract, a hand-derived schedule, or a conservation law—not from the current implementation's output.
3. Update the matching model contract and user documentation in the same change.
4. Keep generated files under `output/` out of Git. Do not rewrite retained evidence or input fixtures to make a new result pass.
5. Run the relevant unit and integration tests, then `make quality-gate` before submitting the change.
6. Describe the motivation, semantic impact, test evidence, and any remaining limitations in the pull request.

## Scientific and compatibility constraints

- Do not mix static planning, online CPU scheduling, and coherent online target assignment in one comparison population.
- Do not change event schemas, metric definitions, retained evidence, or protocol identities without explicit review.
- Do not add silent fallbacks. Unsupported combinations and invalid inputs must fail explicitly with actionable errors.
- Establish every simulation run through `SimulationSession`; do not run simulations concurrently inside one JVM.
- Keep dataset families and their evidence boundaries separate. Never edit frozen DAX or input bytes to accommodate a semantic change.
- Preserve deterministic iteration and summation on evidence paths, named random streams, and Java/Python auditor alignment.
- Preserve legal license notices and executable directives. Do not add commentary-only source comments; place user-facing explanations in the appropriate document.

## Documentation and code style

### Documentation

- The [project front page](<README.md>) states purpose, entry points, capabilities, and research
  boundaries. The [documentation index](<docs/README.md>) is organized by user question; each
  operation or definition has one maintained location, and other pages link to it.
- Guides explain usage, contract documents state implemented semantics, and the build guide states
  verification commands. Process records, round notes, changelogs, and superseded drafts are not
  kept in documents; history lives in Git.
- Each page has one descriptive H1 without round, phase, or status labels. Interfaces, enums,
  format versions, and protocol IDs keep their actual names.
- Local links use relative paths in angle brackets. Do not link machine-specific paths or generated
  output that a standard checkout lacks; mention such paths in code spans with their origin.
- Tag code blocks with a language; state working directory, dependencies, and output-directory
  requirements for commands.
- Keep units, value domains, state meanings, failure behavior, and proof scope. Do not copy
  volatile test counts, branch names, timings, or hash values into documents.
- Every new document is indexed in `docs/README.md`. After changing documentation, run
  `python3 -B scripts/check-docs.py`; when pages move, also search Java comments, tests, scripts,
  and protocol identities for the old path.

### Java source

- The rule ledger is the [checkstyle configuration](<config/checkstyle/checkstyle.xml>) with the
  [suppressions](<config/checkstyle/suppressions.xml>). Tier-A rules (naming contracts, duplicate
  imports, empty statements, the `equals`/`hashCode` contract, string `==`, `default` placement)
  fail the build from `mvn validate`; Tier-B rules (format, length, complexity) are warnings.
- Rules only tighten: a Tier-B rule whose backlog reaches zero is promoted to Tier-A, and any
  relaxation is a contract change that needs human review.
- `org.cloudbus` upstream code is exempt from the style gates. Tests are exempt from size and
  complexity metrics but not from Tier-A rules. The `examples` tree is exempt from style-class
  rules but not from Tier-A rules.
- Format, import, and naming cleanups are committed separately with a `format-only:` prefix.
- Javadoc on public parsers, builders, runners, and validators documents parameters, returns, and
  failure conditions. Do not add template prose to trivial accessors.
- `simulator/src/main/java/org/cloudbus/` keeps its upstream copyright, license, and author
  notices.

### Language

The repository is English. `make language` (`scripts/check-language.py`) rejects CJK characters
in tracked text files. The only exception is text inside frozen `.dax` fixtures, whose bytes are
part of a recorded input identity and must never be edited.

## Verification

Before requesting review, run:

```bash
make quality-gate
```

For changes to scheduling, execution, evidence, or metric semantics, also run the registered mutation battery on a clean committed tree with no concurrent builds:

```bash
python3 -B scripts/mutation-gate.py --all-modules --output <new-report.json>
```

A green build verifies the declared internal consistency and regression coverage. It does not establish production-cluster calibration, universal algorithm superiority, or exact reproduction of every paper table.
