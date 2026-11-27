.PHONY: demo ci all build test verify audit clean clean-output coverage install package rebuild mutation-gate mutation-gate-experiments mutation-gate-all python-gate quality-gate extended-corpus language help

all: build

demo:
	@echo "Running the online scheduling comparison; the report opens from output/demo/index.html"
	mvn -q -pl :workflowsim-workbench -am compile exec:java \
		-Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
		-Dexec.args="run experiments/configs/online-comparison.json output/demo"
	@echo "Done: output/demo/index.html"

build:
	@echo "Compiling the core and experiment modules..."
	mvn compile

test:
	@echo "Running the unit/semantic tests of both modules..."
	mvn test

verify:
	@echo "Running the complete test suite (including the experiment module)..."
	mvn verify

audit:
	mvn -Pjavadoc clean verify

clean:
	@echo "Removing the build output..."
	mvn clean

clean-output:
	@echo "Removing uncompressed study/workbench outputs (all output/*.tar.gz originals are kept)..."
	rm -rf output/demo/ output/workbench*/ output/sensitivity-r13/ output/sensitivity-r13-v2/ \
		output/network-study-r10*/ output/peft-comparison-r12/ \
		output/benchmark-standard-v1/ output/benchmark-standard-power-v1/ \
		output/benchmark-standard-carbon-v1/ output/benchmark-standard-dvfs-v1/
	@echo "Done. Retained evidence originals: output/*.tar.gz"

coverage:
	@echo "Generating the test coverage report..."
	mvn verify jacoco:report
	@echo ""
	@echo "Coverage reports written:"
	@echo "  core module:       simulator/target/site/jacoco/index.html"
	@echo "  experiment module: experiments/target/site/jacoco/index.html"

install:
	@echo "Installing into the local Maven repository..."
	mvn install

package:
	@echo "Packaging (tests skipped)..."
	mvn package -DskipTests
	@echo ""
	@echo "Artifacts:"
	@echo "  simulator/target/workflowsim-1.0.jar"
	@echo "  experiments/target/workflowsim-workbench-1.0.jar"

rebuild: clean build

mutation-gate:
	@echo "Running the simulator mutation gate (clean working tree required)..."
	python3 -B scripts/mutation-gate.py --output output/mutation-gate/mutation-gate-$$(date +%Y-%m-%d).json

mutation-gate-experiments:
	@echo "Running the experiments mutation gate (clean working tree required)..."
	python3 -B scripts/mutation-gate.py --module experiments --output output/mutation-gate/mutation-gate-experiments-$$(date +%Y-%m-%d).json

mutation-gate-all:
	$(MAKE) mutation-gate
	$(MAKE) mutation-gate-experiments

python-gate:
	@set -e; for test_file in scripts/test-*.py; do \
		PYTHONDONTWRITEBYTECODE=1 python3 -B "$$test_file"; \
	done
	python3 -B scripts/check-docs.py
	python3 -B scripts/check-upload-safety.py
	python3 -B scripts/check-license-compliance.py
	python3 -B scripts/check-dataset-indexes.py
	$(MAKE) language

quality-gate: audit python-gate

ci:
	@echo "Running the steps of the GitHub Actions workflow in this checkout..."
	mvn -B -Pjavadoc verify
	python3 -B scripts/test-check-docs.py
	python3 -B scripts/check-docs.py
	python3 -B scripts/test-check-upload-safety.py
	python3 -B scripts/check-upload-safety.py
	python3 -B scripts/test-check-language.py
	python3 -B scripts/check-language.py
	python3 -B scripts/test-check-dataset-indexes.py
	python3 -B scripts/check-dataset-indexes.py
	@set -e; for test_file in scripts/test-*.py; do \
		PYTHONDONTWRITEBYTECODE=1 python3 -B "$$test_file"; \
	done
	@set -e; for fixtures in network-ci file-lifecycle-ci storage-lifecycle-ci dataflow-assignment-ci; do \
		rm -rf "output/$$fixtures"; \
	done
	mvn -B -Pcore-exec -pl :workflowsim -am test-compile exec:java \
		-Dexec.mainClass=org.workflowsim.data.NetworkLedgerPythonFixtures \
		-Dexec.classpathScope=test -Dexec.args="\"$$PWD/output/network-ci\""
	mvn -B -Pcore-exec -pl :workflowsim -am test-compile exec:java \
		-Dexec.mainClass=org.workflowsim.data.v2.FileLifecyclePythonFixtures \
		-Dexec.classpathScope=test -Dexec.args="\"$$PWD/output/file-lifecycle-ci\""
	mvn -B -Pcore-exec -pl :workflowsim -am test-compile exec:java \
		-Dexec.mainClass=org.workflowsim.data.v2.StorageLifecyclePythonFixtures \
		-Dexec.classpathScope=test -Dexec.args="\"$$PWD/output/storage-lifecycle-ci\""
	mvn -B -Pcore-exec -pl :workflowsim -am test-compile exec:java \
		-Dexec.mainClass=org.workflowsim.data.v2.DataflowAssignmentPythonFixtures \
		-Dexec.classpathScope=test -Dexec.args="\"$$PWD/output/dataflow-assignment-ci\""
	npm ci --prefix scripts --ignore-scripts --no-audit --no-fund
	scripts/node_modules/.bin/playwright-core install chromium
	rm -rf output/workbench-ci
	mvn -B -pl :workflowsim-workbench -am test-compile exec:java \
		-Dexec.mainClass=org.workflowsim.experiments.workbench.WorkbenchBrowserFixtures \
		-Dexec.classpathScope=test -Dexec.args="\"$$PWD\" \"$$PWD/output/workbench-ci\""
	node scripts/verify-report.cjs output/workbench-ci/browser-fixtures.json output/workbench-ci/screenshots
	node scripts/test-report-checker.cjs output/workbench-ci/browser-fixtures.json
	@echo "CI steps passed."

extended-corpus:
	python3 -B scripts/check-dataset-indexes.py
	mvn -Pextended-corpus -pl :workflowsim-workbench -am -Dtest=MontageExtendedCorpusTest -Dsurefire.failIfNoSpecifiedTests=false test

language:
	@echo "Checking that the tree is maintained in English..."
	python3 -B scripts/test-check-language.py
	python3 -B scripts/check-language.py

help:
	@echo "WfLedger commands:"
	@echo ""
	@echo "  make demo     - run the example experiment and write output/demo/index.html"
	@echo "  make build    - compile the core and experiment modules"
	@echo "  make test     - unit/semantic tests of both modules"
	@echo "  make verify   - full tests and the coverage gate of both modules"
	@echo "  make audit    - clean build plus joint acceptance, including the core API documentation"
	@echo "  make python-gate - independent Python, docs, language, and dataset checks"
	@echo "  make quality-gate - complete local acceptance surface (audit + python-gate)"
	@echo "  make ci       - the steps of the GitHub Actions workflow (needs Node and Chromium)"
	@echo "  make extended-corpus - opt-in full-corpus acceptance (all 17 Montage inputs required)"
	@echo "  make clean    - remove the build output"
	@echo "  make clean-output - remove regenerable uncompressed output/ study dirs (keeps all .tar.gz originals)"
	@echo "  make coverage - generate the test coverage report"
	@echo "  make package  - package the JARs (tests skipped)"
	@echo "  make install  - install into the local Maven repository"
	@echo "  make rebuild  - clean and rebuild"
	@echo "  make mutation-gate - simulator zero-survival mutation gate (clean working tree required)"
	@echo "  make mutation-gate-experiments - experiments zero-survival mutation gate (clean working tree required)"
	@echo "  make mutation-gate-all - both registered mutation families (clean working tree required)"
	@echo "  make language - reject CJK characters in the tracked text files"
	@echo "  make help     - show this help text"
	@echo ""
	@echo "Detailed documentation: docs/getting-started/BUILD.md"