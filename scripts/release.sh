#!/bin/sh
set -eu

usage() {
  cat <<'EOF'
Usage: sh scripts/release.sh [VERSION] [options]
  --build-only       Build and verify release assets only; do not change Git.
  --no-publish       Commit and tag locally; do not push or create a release.
  --pr               Use a release branch and, when publishing, open a PR.
  --next VERSION     Next development version (default: next patch-SNAPSHOT).
  --notes-file FILE  Use this file for release notes instead of generated notes.
  --skip-tests       Skip both Java and JavaScript tests.
  --any-branch       Allow a release from a branch other than main.
  -h, --help         Show this help.

Publishing pushes the branch and tag and creates a GitHub DRAFT release.
Release operations require confirmation; --build-only does not.
EOF
}

die() { printf 'release: ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf 'release: %s\n' "$*"; }
confirm() {
  printf '%s [y/N] ' "$1"
  read -r answer || answer=
  case "$answer" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

VERSION= NEXT= NOTES_FILE=
BUILD_ONLY=0 NO_PUBLISH=0 VIA_PR=0 SKIP_TESTS=0 ANY_BRANCH=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --build-only) BUILD_ONLY=1 ;;
    --no-publish) NO_PUBLISH=1 ;;
    --pr) VIA_PR=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    --any-branch) ANY_BRANCH=1 ;;
    --next|--notes-file)
      option=$1
      shift
      [ "$#" -gt 0 ] || die "$option requires a value"
      if [ "$option" = --next ]; then NEXT=$1; else NOTES_FILE=$1; fi ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown option: $1" ;;
    *) [ -z "$VERSION" ] || die "only one version may be specified"; VERSION=$1 ;;
  esac
  shift
done

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
command -v mvn >/dev/null 2>&1 || die "mvn is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required to verify the JAR"
maven_info=$(mvn -version) || die "could not run mvn"
java_major=$(printf '%s\n' "$maven_info" | sed -n 's/^Java version: \([0-9][0-9]*\).*/\1/p')
[ -n "$java_major" ] && [ "$java_major" -ge 21 ] || die "Maven must run with Java 21 or newer"
if [ "$SKIP_TESTS" -eq 0 ]; then
  command -v node >/dev/null 2>&1 || die "Node.js is required for JavaScript tests"
  command -v git >/dev/null 2>&1 || die "git is required for script tests"
fi

if printf x | sed -b p >/dev/null 2>&1; then SED_BINARY=-b; else SED_BINARY=; fi
pom_version() {
  sed $SED_BINARY -n '/<artifactId>md2docu<\/artifactId>/{n;s|.*<version>\([^<]*\)</version>.*|\1|p;}' pom.xml | tr -d '\r'
}
set_pom_version() {
  sed $SED_BINARY "/<artifactId>md2docu<\/artifactId>/{n;s|<version>[^<]*</version>|<version>$1</version>|;}" "$BACKUP" > pom.xml
  [ "$(pom_version)" = "$1" ] || die "could not set project version to $1"
}
valid_release() { printf '%s\n' "$1" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'; }
valid_snapshot() { valid_release "${1%-SNAPSHOT}" && [ "${1%-SNAPSHOT}" != "$1" ]; }
CURRENT=$(pom_version)
if [ -z "$VERSION" ]; then
  valid_snapshot "$CURRENT" || die "project version must be X.Y.Z-SNAPSHOT, or specify VERSION"
  VERSION=${CURRENT%-SNAPSHOT}
fi
valid_release "$VERSION" || die "release version must be X.Y.Z"
if [ -z "$NEXT" ]; then
  major=${VERSION%%.*}; rest=${VERSION#*.}; minor=${rest%%.*}; patch=${rest#*.}
  NEXT="$major.$minor.$((patch + 1))-SNAPSHOT"
fi
valid_snapshot "$NEXT" || die "--next must be X.Y.Z-SNAPSHOT"
[ -z "$NOTES_FILE" ] || [ -f "$NOTES_FILE" ] || die "notes file not found: $NOTES_FILE"
TAG="v$VERSION"
JAR="md2docu-$VERSION.jar"

if [ "$BUILD_ONLY" -eq 0 ]; then
  command -v git >/dev/null 2>&1 || die "git is required"
  BRANCH=$(git branch --show-current)
  [ -n "$BRANCH" ] || die "detached HEAD is not supported"
  [ "$BRANCH" = main ] || [ "$ANY_BRANCH" -eq 1 ] || die "release from main or use --any-branch"
  status=$(git status --porcelain) || die "could not check working tree"
  [ -z "$status" ] || die "working tree must be clean"
  git fetch --quiet origin || die "could not fetch origin"
  git rev-parse --verify "refs/remotes/origin/$BRANCH" >/dev/null 2>&1 || die "origin/$BRANCH does not exist"
  [ "$(git rev-list --count "HEAD..origin/$BRANCH")" = 0 ] || die "$BRANCH is behind origin"
  git show-ref --verify --quiet "refs/tags/$TAG" && die "tag $TAG already exists locally"
  remote_tag=$(git ls-remote --tags origin "refs/tags/$TAG") || die "could not check remote tags"
  [ -z "$remote_tag" ] || die "tag $TAG already exists on origin"
  RBRANCH="release/$VERSION"
  if [ "$VIA_PR" -eq 1 ]; then
    git show-ref --verify --quiet "refs/heads/$RBRANCH" && die "branch $RBRANCH already exists locally"
    remote_branch=$(git ls-remote --heads origin "refs/heads/$RBRANCH") || die "could not check remote branches"
    [ -z "$remote_branch" ] || die "branch $RBRANCH already exists on origin"
  fi
  if [ "$NO_PUBLISH" -eq 0 ]; then
    command -v gh >/dev/null 2>&1 || die "GitHub CLI is required; use --no-publish for local operations"
    gh auth status || die "GitHub CLI authentication failed"
  fi
  info "release $VERSION from $BRANCH; next version: $NEXT"
  confirm "Build this release and prepare Git changes?" || die "aborted before building"
  confirm "Is $VERSION the correct release version?" || die "aborted before building"
fi

BACKUP=$(mktemp "${TMPDIR:-/tmp}/md2docu-pom.XXXXXX") || die "could not create POM backup"
cp pom.xml "$BACKUP" || die "could not back up pom.xml"
RESTORE=1
cleanup() {
  if [ "$RESTORE" -eq 1 ]; then
    if ! cp "$BACKUP" pom.xml; then
      printf 'release: ERROR: restore failed; original POM is at %s\n' "$BACKUP" >&2
      return 1
    fi
  fi
  rm -f "$BACKUP"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
set_pom_version "$VERSION"
if [ "$SKIP_TESTS" -eq 1 ]; then
  info "building without tests"
  mvn -B clean package -DskipTests || die "Maven build failed"
else
  info "building and running Java and JavaScript tests"
  mvn -B clean verify || die "Maven build or Java tests failed"
  node --test src/test/js/preview.test.cjs src/test/js/pdf-environment.test.cjs src/test/js/scripts.test.cjs || die "JavaScript tests failed"
fi

SOURCE="target/$JAR"
[ -f "$SOURCE" ] || die "built JAR not found: $SOURCE"
version=$(unzip -p "$SOURCE" META-INF/maven/com.md2docu/md2docu/pom.properties | tr -d '\r' | sed -n 's/^version=//p')
start=$(unzip -p "$SOURCE" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Start-Class: //p')
[ "$version" = "$VERSION" ] || die "JAR version '$version' does not match $VERSION"
[ "$start" = com.md2docu.Md2docuApplication ] || die "unexpected JAR Start-Class: $start"
OUT=target/release
mkdir -p "$OUT"
cp "$SOURCE" "$OUT/$JAR"
if command -v sha256sum >/dev/null 2>&1; then
  hash=$(sha256sum < "$OUT/$JAR" | cut -d ' ' -f1)
elif command -v shasum >/dev/null 2>&1; then
  hash=$(shasum -a 256 < "$OUT/$JAR" | cut -d ' ' -f1)
else
  die "sha256sum or shasum is required"
fi
printf '%s  %s\n' "$hash" "$JAR" > "$OUT/SHA256SUMS.txt"
cp MANUAL.md LICENSE NOTICE "$OUT/"
info "verified release assets in $OUT"
if [ "$BUILD_ONLY" -eq 1 ]; then
  info "build only: no Git changes"
  exit 0
fi

confirm "Commit release $TAG and next version $NEXT?" || die "aborted before committing"
if [ "$VIA_PR" -eq 1 ]; then
  git switch -c "$RBRANCH" || die "could not create $RBRANCH"
fi
git add pom.xml
git commit -m "chore: release $TAG" || die "release commit failed"
RESTORE=0
git tag -a "$TAG" -m "Release $VERSION" || die "release commit created; create tag $TAG manually"
set_pom_version "$NEXT"
git add pom.xml
git commit -m "chore: begin $NEXT" || die "tag $TAG created; commit the next version manually"

if [ "$VIA_PR" -eq 1 ]; then PUSH_BRANCH=$RBRANCH; else PUSH_BRANCH=$BRANCH; fi
if [ "$NO_PUBLISH" -eq 1 ]; then
  info "local release created; nothing was pushed"
  info "push with: git push origin $PUSH_BRANCH $TAG"
  info "create a draft release for $TAG with the files in $OUT"
  if [ "$VIA_PR" -eq 1 ]; then
    info "open a PR from $RBRANCH to $BRANCH and merge with a merge commit"
  fi
  exit 0
fi

git push origin "$PUSH_BRANCH" "$TAG" || die "push failed; local commits and tag remain"
set -- "$OUT/$JAR" "$OUT/SHA256SUMS.txt" "$OUT/MANUAL.md" "$OUT/LICENSE" "$OUT/NOTICE"
if [ -n "$NOTES_FILE" ]; then
  gh release create "$TAG" "$@" --verify-tag --draft --title "md2docu $VERSION" --notes-file "$NOTES_FILE" || die "could not create draft release; branch and tag were pushed"
else
  gh release create "$TAG" "$@" --verify-tag --draft --title "md2docu $VERSION" --generate-notes || die "could not create draft release; branch and tag were pushed"
fi
if [ "$VIA_PR" -eq 1 ]; then
  gh pr create --base "$BRANCH" --head "$RBRANCH" --title "chore: release $TAG" --body "Merge with a merge commit so tag $TAG remains reachable. Review the draft release and publish it after merging." || die "draft created, but PR creation failed"
fi
info "draft release created for $TAG; review and publish it manually"
