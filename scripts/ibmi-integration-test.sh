#!/bin/sh
# Builds the driver, copies it and the test classes to an IBM i over SSH, and runs the integration tests there.
#
#   scripts/ibmi-integration-test.sh [user@]host [remote-dir]
#
# Requires: mvn, ssh/scp access to the IBM i, and Java 8+ on the IBM i.
set -e
HOST="${1:?usage: $0 [user@]host [remote-dir]}"
REMOTE_DIR="${2:-db2shell-jdbc}"
JUNIT_VERSION=1.10.2
cd "$(dirname "$0")/.."

mvn -q package
mvn -q dependency:copy -Dartifact=org.junit.platform:junit-platform-console-standalone:$JUNIT_VERSION \
    -DoutputDirectory=target/it

JAR=$(ls target/*-jar-with-dependencies.jar | head -1)
(cd target && tar cf it.tar test-classes it)
ssh "$HOST" "mkdir -p $REMOTE_DIR"
scp -q "$JAR" "$HOST:$REMOTE_DIR/db2-shell-jdbc.jar"
scp -q target/it.tar "$HOST:$REMOTE_DIR/it.tar"
ssh "$HOST" "cd $REMOTE_DIR && rm -rf test-classes it && tar xf it.tar && \
  java -jar it/junit-platform-console-standalone-$JUNIT_VERSION.jar execute \
    -cp db2-shell-jdbc.jar:test-classes --select-class io.github.db2shell.jdbc.IbmiIntegrationTest \
    --details=tree --disable-banner --disable-ansi-colors"
