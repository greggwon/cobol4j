JAVA_HOME ?= /Users/gregg/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home
MVN = JAVA_HOME=$(JAVA_HOME) /usr/local/bin/mvn
VERSION = 0.1.0-SNAPSHOT
TARGET = target

.PHONY: all clean test compile package install lib runner sources javadoc

# Build everything: compile, test, package all artifacts
all: test package
	@echo ""
	@echo "=== Build complete ==="
	@echo "Library:  $(TARGET)/cobol4j-$(VERSION).jar"
	@echo "Runner:   $(TARGET)/cobol4j-$(VERSION)-runner.jar"
	@echo "Sources:  $(TARGET)/cobol4j-$(VERSION)-sources.jar"
	@echo ""

# Compile only (no tests, no packaging)
compile:
	$(MVN) compile -q

# Run all tests
test:
	$(MVN) test

# Package all artifacts (includes compile)
package:
	$(MVN) package -DskipTests -q

# Build just the library JAR (thin, no dependencies)
lib:
	$(MVN) jar:jar -q
	@echo "Library: $(TARGET)/cobol4j-$(VERSION).jar"

# Build the runner JAR (fat, self-contained, executable)
runner: package
	@echo "Runner: $(TARGET)/cobol4j-$(VERSION)-runner.jar"
	@echo "Run with: java -jar $(TARGET)/cobol4j-$(VERSION)-runner.jar run <source.cbl>"

# Build the sources JAR
sources:
	$(MVN) source:jar-no-fork -q
	@echo "Sources: $(TARGET)/cobol4j-$(VERSION)-sources.jar"

# Install to local Maven repository (~/.m2)
install:
	$(MVN) install -DskipTests -q
	@echo "Installed to local Maven repository"
	@echo "Dependency:"
	@echo "  <groupId>org.cobol4j</groupId>"
	@echo "  <artifactId>cobol4j</artifactId>"
	@echo "  <version>$(VERSION)</version>"

# Generate API documentation into docs/javadoc/
javadoc:
	$(MVN) javadoc:javadoc -q
	@echo "Javadoc: docs/javadoc/apidocs/index.html"

# Clean build artifacts
clean:
	$(MVN) clean -q
	@echo "Cleaned"
