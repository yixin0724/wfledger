

.PHONY: all build test verify audit clean clean-output coverage install package rebuild mutation-gate mutation-gate-experiments mutation-gate-all python-gate quality-gate extended-corpus language help

all: build

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
	rm -rf output/workbench*/ output/sensitivity-r13/ output/sensitivity-r13-v2/ \
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

extended-corpus:
	python3 -B scripts/check-dataset-indexes.py
	mvn -Pextended-corpus -pl :workflowsim-workbench -am -Dtest=MontageExtendedCorpusTest -Dsurefire.failIfNoSpecifiedTests=false test

language:
	@echo "Checking that the tree is maintained in English..."
	python3 -B scripts/test-check-language.py
	python3 -B scripts/check-language.py

help:
	@echo "WorkflowSim build commands:"
	@echo ""
	@echo "  make build    - compile the core and experiment modules"
	@echo "  make test     - unit/semantic tests of both modules"
	@echo "  make verify   - full tests and the coverage gate of both modules"
	@echo "  make audit    - clean build plus joint acceptance, including the core API documentation"
	@echo "  make python-gate - independent Python, docs, language, and dataset checks"
	@echo "  make quality-gate - complete local acceptance surface (audit + python-gate)"
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