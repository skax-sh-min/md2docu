const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const template = readFileSync(join(__dirname, '..', '..', 'main', 'resources', 'templates', 'index.html'), 'utf8');
const script = template.match(/<script>([\s\S]*?)<\/script>/)[1];
const source = section("  const formatSelect = document.getElementById('opt-format');", '  let selectedFile = null;')
  + section('  async function conversionJson(url, options) {', '  function showResult(data) {');

function section(start, end) {
  const first = script.indexOf(start);
  const last = script.indexOf(end, first + start.length);
  assert(first >= 0 && last > first);
  return script.slice(first, last);
}

function harness(initialFormat = 'docx') {
  let resolve;
  let reject;
  const response = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  const classes = new Set(['alert', 'alert-warning', 'd-none']);
  const listeners = [];
  const format = { value: initialFormat, addEventListener(event, callback) {
    assert.equal(event, 'change');
    listeners.push(callback);
  }};
  const warning = {
    textContent: '', innerHTML: '',
    classList: {
      contains(name) { return classes.has(name); },
      toggle(name, force) { if (force) classes.add(name); else classes.delete(name); }
    }
  };
  vm.runInNewContext(source, {
    document: { getElementById(id) {
      assert(template.includes('id="' + id + '"'));
      if (id === 'opt-format') return format;
      if (id === 'pdf-warning') return warning;
      throw new Error('Unexpected element: ' + id);
    }},
    fetch(url) { assert.equal(url, '/api/system/info'); return response; }
  });
  return {
    warning,
    select(value) { format.value = value; listeners.forEach(callback => callback()); },
    succeed(data, status = 200) { resolve({ ok: status >= 200 && status < 300, status, json: async () => data }); },
    invalidJson() { resolve({ ok: true, status: 200, json: async () => { throw new SyntaxError('Bad JSON'); } }); },
    fail(error = new Error('Offline')) { reject(error); },
    hidden() { return classes.has('d-none'); }
  };
}

const flush = () => new Promise(resolve => setImmediate(resolve));
function report(state, os) {
  const fonts = state === 'none' ? []
    : [{ family: 'Test', name: 'Test', weight: 400, style: 'normal', koreanSampleSupported: state === 'korean' }];
  return { os, pdfKoreanWarning: state !== 'korean', pdfFonts: { fonts, issues: [], sample: '한글', checkedAt: '2026-10-27T00:00:00Z' } };
}

for (const os of ['Windows', 'Linux', 'macOS']) {
  for (const state of ['none', 'latin', 'korean']) {
    test(`${os} with ${state} fonts follows actual capabilities rather than the OS name`, async () => {
      const h = harness('pdf');
      assert.match(h.warning.textContent, /확인 중/);
      h.succeed(report(state, os));
      await flush();
      assert.equal(h.hidden(), false);
      if (state === 'korean') {
        assert(h.warning.classList.contains('alert-info'));
        assert(!h.warning.classList.contains('alert-warning'));
        assert.match(h.warning.textContent, /기본 한글 샘플 지원을 확인/);
        assert.match(h.warning.textContent, /보장하지는 않습니다/);
        assert.match(h.warning.textContent, /재시작/);
      } else {
        assert(h.warning.classList.contains('alert-warning'));
        assert(!h.warning.classList.contains('alert-info'));
        assert.match(h.warning.textContent, /DOCX/);
        assert.match(h.warning.textContent, /변환은 계속/);
        assert.match(h.warning.textContent, state === 'none' ? /사용할 수 있는 PDF 글꼴/ : /기본 한글 샘플/);
      }
    });
  }
}

test('selecting PDF before the delayed response updates immediately when the response arrives', async () => {
  const h = harness();
  assert(h.hidden());
  h.select('pdf');
  assert(!h.hidden());
  assert.match(h.warning.textContent, /확인 중/);
  h.succeed(report('none'));
  await flush();
  assert(!h.hidden());
  assert.match(h.warning.textContent, /찾지 못했습니다/);
});

test('a late response does not show PDF guidance after switching back to DOCX', async () => {
  const h = harness('pdf');
  h.select('docx');
  h.succeed(report('none'));
  await flush();
  assert(h.hidden());
  h.select('pdf');
  assert(!h.hidden());
  assert.match(h.warning.textContent, /찾지 못했습니다/);
});

test('selecting PDF after a completed environment response uses the same update path', async () => {
  const h = harness();
  h.succeed(report('korean'));
  await flush();
  assert(h.hidden());
  h.select('pdf');
  assert(!h.hidden());
  assert(h.warning.classList.contains('alert-info'));
});

for (const [index, data] of [null, [], {}, { pdfKoreanWarning: 'false' }, { pdfKoreanWarning: false },
  { pdfKoreanWarning: false, pdfFonts: {} }, { pdfKoreanWarning: false, pdfFonts: { fonts: {} } }].entries()) {
  test(`invalid successful environment response ${index + 1} is visible as an unverified state`, async () => {
    const h = harness('pdf');
    h.succeed(data);
    await flush();
    assert(!h.hidden());
    assert(h.warning.classList.contains('alert-warning'));
    assert.match(h.warning.textContent, /응답 형식이 올바르지 않습니다/);
    assert.match(h.warning.textContent, /한글 표시는 확인되지 않았습니다/);
    assert.doesNotMatch(h.warning.textContent, /샘플 지원을 확인했습니다/);
  });
}

test('network failures are not swallowed and remain hidden for DOCX until PDF is selected', async () => {
  const h = harness();
  h.fail();
  await flush();
  assert(h.hidden());
  h.select('pdf');
  assert.match(h.warning.textContent, /조회 실패.*Offline/);
  assert.match(h.warning.textContent, /새로고침/);
  h.select('docx');
  assert(h.hidden());
});

test('HTTP errors are shown as text rather than HTML', async () => {
  const h = harness('pdf');
  h.succeed({ error: '<b>Server unavailable</b>' }, 500);
  await flush();
  assert.match(h.warning.textContent, /<b>Server unavailable<\/b>/);
  assert.equal(h.warning.innerHTML, '');
  assert(h.warning.classList.contains('alert-warning'));
});

test('invalid JSON has an explicit error and does not imply Korean support', async () => {
  const h = harness('pdf');
  h.invalidJson();
  await flush();
  assert.match(h.warning.textContent, /올바른 JSON/);
  assert.match(h.warning.textContent, /확인되지 않았습니다/);
});
