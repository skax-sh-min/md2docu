const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..', '..', '..');
const bash = process.env.MD2DOCU_TEST_BASH || process.env.SCRIPT_BASH ||
  (process.platform === 'win32' ? 'C:\\Program Files\\Git\\bin\\bash.exe' : 'bash');

function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'md2docu scripts '));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'scripts'));
  fs.mkdirSync(path.join(dir, 'bin'));
  for (const name of ['release.sh', 'run_by_jar.sh']) {
    fs.copyFileSync(path.join(root, 'scripts', name), path.join(dir, 'scripts', name));
  }
  const pom = '<project>\r\n<artifactId>md2docu</artifactId>\r\n<version>1.2.3-SNAPSHOT</version>\r\n</project>\r\n';
  fs.writeFileSync(path.join(dir, 'pom.xml'), pom);
  for (const name of ['MANUAL.md', 'LICENSE', 'NOTICE']) fs.writeFileSync(path.join(dir, name), name);
  const mock = (name, body) => {
    const file = path.join(dir, 'bin', name);
    fs.writeFileSync(file, `#!/bin/sh\nset -eu\n${body}\n`);
    fs.chmodSync(file, 0o755);
  };
  mock('mvn', `
    if [ "$1" = -version ]; then echo 'Java version: 21'; exit 0; fi
    echo "$*" >> calls
    [ "\${FAIL_BUILD:-0}" != 1 ] || exit 7
    version=$(sed -n 's|.*<version>\\([^<]*\\)</version>.*|\\1|p' pom.xml | tr -d '\\r')
    mkdir -p target/maven-archiver
    printf 'version=%s\\n' "$version" > target/maven-archiver/pom.properties
    printf 'jar' > "target/md2docu-$version.jar"
  `);
  mock('node', 'echo "node $*" >> calls');
  mock('gh', 'printf "gh" >> calls; printf " <%s>" "$@" >> calls; printf "\\n" >> calls');
  mock('unzip', `
    if [ "\${BAD_JAR:-0}" = 1 ]; then echo 'version=0.0.0'; exit 0; fi
    case "$3" in
      *pom.properties) sed -n 's|.*<version>\\([^<]*\\)</version>.*|version=\\1|p' pom.xml | tr -d '\\r' ;;
      *) echo 'Start-Class: com.md2docu.Md2docuApplication' ;;
    esac
  `);
  mock('java', 'printf "%s\\n" "$@" > java-args');
  const run = (script, args = [], env = {}, input = '') => spawnSync(bash, [
    '-c', 'cd "$1"; PATH="$PWD/bin:$PATH"; export PATH; shift; exec sh "$@"',
    'test', dir.replaceAll('\\', '/'), `scripts/${script}`, ...args
  ], { encoding: 'utf8', env: { ...process.env, ...env }, input });
  return { dir, pom, run };
}

test('build-only verifies both test suites, creates assets and restores the exact POM', t => {
  const f = fixture(t);
  const result = f.run('release.sh', ['--build-only']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(fs.readFileSync(path.join(f.dir, 'pom.xml'), 'utf8'), f.pom);
  assert.match(fs.readFileSync(path.join(f.dir, 'calls'), 'utf8'), /clean verify[\s\S]*node --test/);
  assert.match(fs.readFileSync(path.join(f.dir, 'target', 'release', 'SHA256SUMS.txt'), 'utf8'), /  md2docu-1\.2\.3\.jar\n$/);
  for (const file of ['md2docu-1.2.3.jar', 'MANUAL.md', 'LICENSE', 'NOTICE']) {
    assert.ok(fs.existsSync(path.join(f.dir, 'target', 'release', file)));
  }
});

test('failed build restores the original POM and creates no release', t => {
  const f = fixture(t);
  const result = f.run('release.sh', ['--build-only'], { FAIL_BUILD: '1' });
  assert.notEqual(result.status, 0);
  assert.equal(fs.readFileSync(path.join(f.dir, 'pom.xml'), 'utf8'), f.pom);
  assert.equal(fs.existsSync(path.join(f.dir, 'target', 'release')), false);
});

test('a JAR with mismatched metadata is rejected and the POM is restored', t => {
  const f = fixture(t);
  const result = f.run('release.sh', ['--build-only', '--skip-tests'], { BAD_JAR: '1' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /JAR version '0\.0\.0' does not match/);
  assert.equal(fs.readFileSync(path.join(f.dir, 'pom.xml'), 'utf8'), f.pom);
  assert.equal(fs.existsSync(path.join(f.dir, 'target', 'release')), false);
});

test('build-only supports an explicit version and skipping tests', t => {
  const f = fixture(t);
  const result = f.run('release.sh', ['2.0.0', '--build-only', '--skip-tests']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(fs.readFileSync(path.join(f.dir, 'pom.xml'), 'utf8'), f.pom);
  assert.match(fs.readFileSync(path.join(f.dir, 'calls'), 'utf8'), /clean package -DskipTests/);
  assert.ok(fs.existsSync(path.join(f.dir, 'target', 'release', 'md2docu-2.0.0.jar')));
});

function initGit(f) {
  const git = (...args) => {
    const result = spawnSync('git', ['-C', f.dir, ...args], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git('init', '-b', 'main');
  git('config', 'user.name', 'Release Test');
  git('config', 'user.email', 'release-test@example.invalid');
  git('add', '.');
  git('commit', '-m', 'test: initialize fixture');
  const remote = path.join(f.dir, '.git', 'test-remote.git');
  git('init', '--bare', remote);
  git('remote', 'add', 'origin', remote);
  git('push', '-u', 'origin', 'main');
  return git;
}

test('local PR release creates tagged and next-version commits without pushing', t => {
  const f = fixture(t);
  const git = initGit(f);
  const result = f.run('release.sh', ['--pr', '--no-publish', '--skip-tests'], {}, 'y\ny\ny\n');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(git('branch', '--show-current'), 'release/1.2.3');
  assert.match(git('show', 'v1.2.3:pom.xml'), /<version>1\.2\.3<\/version>/);
  assert.match(git('show', 'HEAD:pom.xml'), /<version>1\.2\.4-SNAPSHOT<\/version>/);
  assert.match(git('show', 'main:pom.xml'), /<version>1\.2\.3-SNAPSHOT<\/version>/);
  assert.equal(git('ls-remote', '--tags', 'origin', 'refs/tags/v1.2.3'), '');
});

test('PR publishing pushes the tag and branch and creates a draft and PR', t => {
  const f = fixture(t);
  fs.writeFileSync(path.join(f.dir, 'notes with spaces.txt'), 'Release notes');
  const git = initGit(f);
  const result = f.run('release.sh', [
    '--pr', '--skip-tests', '--notes-file', 'notes with spaces.txt'
  ], {}, 'y\ny\ny\n');
  assert.equal(result.status, 0, result.stderr);
  assert.match(git('ls-remote', '--tags', 'origin', 'refs/tags/v1.2.3'), /refs\/tags\/v1\.2\.3/);
  assert.match(git('ls-remote', '--heads', 'origin', 'refs/heads/release/1.2.3'), /refs\/heads\/release\/1\.2\.3/);
  const calls = fs.readFileSync(path.join(f.dir, 'calls'), 'utf8');
  assert.match(calls, /<release> <create> <v1\.2\.3>[\s\S]*<--draft>[\s\S]*<--notes-file> <notes with spaces\.txt>/);
  assert.match(calls, /<pr> <create> <--base> <main> <--head> <release\/1\.2\.3>/);
});

test('run script builds the recorded JAR and forwards application arguments', t => {
  const f = fixture(t);
  const result = f.run('run_by_jar.sh', ['--clean-build', '--', '--server.port=9090', 'value with spaces']);
  assert.equal(result.status, 0, result.stderr);
  assert.match(fs.readFileSync(path.join(f.dir, 'calls'), 'utf8'), /clean package/);
  assert.equal(fs.readFileSync(path.join(f.dir, 'java-args'), 'utf8'),
    '-jar\ntarget/md2docu-1.2.3-SNAPSHOT.jar\n--server.port=9090\nvalue with spaces\n');
});

test('run script reports a missing build and rejects unknown options', t => {
  const f = fixture(t);
  assert.match(f.run('run_by_jar.sh').stderr, /build metadata not found/);
  assert.notEqual(f.run('run_by_jar.sh', ['--unknown']).status, 0);
});
