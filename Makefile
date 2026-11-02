# WorkflowSim Makefile
# Shortcuts for the common Maven build commands

.PHONY: all build test verify audit clean coverage install package rebuild mutation-gate help

# default target
all: build

# compile the core and experiment modules
build:
	@echo "Compiling the core and experiment modules..."
	mvn compile

# run the unit/semantic tests of both modules
test:
	@echo "Running the unit/semantic tests of both modules..."
	mvn test

# run the complete test suite (core + experiments, about 2 minutes)
verify:
	@echo "Running the complete test suite (including the experiment module)..."
	mvn verify

# clean build plus joint acceptance, including the core API documentation
audit:
	mvn -Pjavadoc clean verify

# remove the build output
clean:
	@echo "Removing the build output..."
	mvn clean

# generate the test coverage report
coverage:
	@echo "Generating the test coverage report..."
	mvn verify jacoco:report
	@echo ""
	@echo "Coverage reports written:"
	@echo "  core module:       simulator/target/site/jacoco/index.html"
	@echo "  experiment module: experiments/target/site/jacoco/index.html"

# install into the local Maven repository
install:
	@echo "Installing into the local Maven repository..."
	mvn install

# package the JARs, skipping the tests
package:
	@echo "Packaging (tests skipped)..."
	mvn package -DskipTests
	@echo ""
	@echo "Artifacts:"
	@echo "  simulator/target/workflowsim-1.0.jar"
	@echo "  experiments/target/workflowsim-workbench-1.0.jar"

# clean and rebuild
rebuild: clean build

# registered zero-survival mutation gate (about 3-5 minutes; needs a clean working tree,
# and the report is written to output/mutation-gate/)
mutation-gate:
	@echo "Running the registered mutation gate (clean working tree required)..."
	python3 -B scripts/mutation-gate.py --output output/mutation-gate/mutation-gate-$$(date +%Y-%m-%d).json

# show the help text
help:
	@echo "WorkflowSim build commands:"
	@echo ""
	@echo "  make build    - compile the core and experiment modules"
	@echo "  make test     - unit/semantic tests of both modules"
	@echo "  make verify   - full tests and the coverage gate of both modules"
	@echo "  make audit    - clean build plus joint acceptance, including the core API documentation"
	@echo "  make clean    - remove the build output"
	@echo "  make coverage - generate the test coverage report"
	@echo "  make package  - package the JARs (tests skipped)"
	@echo "  make install  - install into the local Maven repository"
	@echo "  make rebuild  - clean and rebuild"
	@echo "  make mutation-gate - registered zero-survival mutation gate (clean working tree required)"
	@echo "  make help     - show this help text"
	@echo ""
	@echo "Detailed documentation: docs/getting-started/BUILD.md"