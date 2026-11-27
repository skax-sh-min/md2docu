#!/bin/sh
set -eu

usage() {
  echo "Usage: sh scripts/run_by_jar.sh [-b|--build|-cb|--clean-build] [-- application-arguments...]"
  echo "Without a build option, run the JAR recorded in target/maven-archiver/pom.properties."
}

BUILD=
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  -b|--build) BUILD=build; shift ;;
  -cb|--clean-build) BUILD=clean; shift ;;
esac
if [ "$#" -gt 0 ]; then
  [ "$1" = -- ] || { echo "run: ERROR: unknown option: $1" >&2; exit 1; }
  shift
fi
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
command -v java >/dev/null 2>&1 || { echo "run: ERROR: java is required" >&2; exit 1; }
if [ -n "$BUILD" ]; then
  command -v mvn >/dev/null 2>&1 || { echo "run: ERROR: mvn is required" >&2; exit 1; }
  if [ "$BUILD" = clean ]; then mvn clean package; else mvn package; fi
fi
PROPERTIES=target/maven-archiver/pom.properties
[ -f "$PROPERTIES" ] || { echo "run: ERROR: build metadata not found; run with --build" >&2; exit 1; }
VERSION=$(sed -n 's/^version=//p' "$PROPERTIES" | tr -d '\r')
[ -n "$VERSION" ] || { echo "run: ERROR: build version is missing" >&2; exit 1; }
JAR="target/md2docu-$VERSION.jar"
[ -f "$JAR" ] || { echo "run: ERROR: JAR not found: $JAR" >&2; exit 1; }
echo "run: starting $JAR"
exec java -jar "$JAR" "$@"
