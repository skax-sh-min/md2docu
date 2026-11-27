const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const template = readFileSync(join(__dirname, '..', '..', 'main', 'resources', 'templates', 'index.html'), 'utf8');
const script = template.match(/<script>([\s\S]*?)<\/script>/)[1];
const ids = new Set([...template.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]));
const source = section('  const previewPane =', "  document.getElementById('btn-convert').addEventListener")
  + section('  async function conversionJson(url, options) {', '  function showResult(data) {');

function section(start, end) {
  const first = script.indexOf(start);
  const last = script.indexOf(end, first + start.length);
  assert(first >= 0 && last > first, 'Actual template handler boundaries must exist');
  return script.slice(first, last);
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

function harness({ honorAbort = false } = {}) {
  const nodes = new Map();
  const requests = [];
  const timers = new Map();
  let timerId = 0;
  let time = 0;

  function element(id) {
    assert(ids.has(id), 'Actual template element must exist: ' + id);
    if (!nodes.has(id)) {
      nodes.set(id, {
        value: id === 'editor' ? '# Initial' : '',
        checked: false, textContent: '', innerHTML: '', className: '',
        attributes: new Map(), listeners: new Map(),
        setAttribute(name, value) { this.attributes.set(name, value); },
        getAttribute(name) { return this.attributes.get(name); },
        addEventListener(event, handler) {
          if (!this.listeners.has(event)) this.listeners.set(event, []);
          this.listeners.get(event).push(handler);
        }
      });
    }
    return nodes.get(id);
  }

  function fetch(url, options) {
    const response = deferred();
    const request = {
      url, options,
      succeed(data, status = 200) {
        response.resolve({ ok: status >= 200 && status < 300, status, json: async () => data });
      },
      headers(body, status = 200) {
        response.resolve({ ok: status >= 200 && status < 300, status, json: () => body });
      },
      fail(error = new Error('Network failed')) { response.reject(error); }
    };
    if (honorAbort) {
      options.signal.addEventListener('abort', () => response.reject(new DOMException('Aborted', 'AbortError')), { once: true });
    }
    requests.push(request);
    return response.promise;
  }

  const context = vm.createContext({
    document: { getElementById: element }, fetch, AbortController,
    setTimeout(callback, delay) {
      const id = ++timerId;
      timers.set(id, { callback, due: time + delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); }
  });
  vm.runInContext(source, context);

  return {
    requests, timers, element,
    pane: element('preview-pane'), status: element('preview-status'), button: element('btn-preview'),
    emit(id, event) {
      for (const listener of element(id).listeners.get(event) || []) listener();
    },
    edit(markdown) { element('editor').value = markdown; this.emit('editor', 'input'); },
    click() { this.emit('btn-preview', 'click'); },
    number(value) { element('opt-numbering').checked = value; this.emit('opt-numbering', 'change'); },
    advance(milliseconds) {
      time += milliseconds;
      for (;;) {
        const next = [...timers.entries()].sort((a, b) => a[1].due - b[1].due)[0];
        if (!next || next[1].due > time) return;
        timers.delete(next[0]);
        next[1].callback();
      }
    }
  };
}

const flush = () => new Promise(resolve => setImmediate(resolve));

async function ready() {
  const h = harness();
  h.requests[0].succeed({ html: '<p>Previous</p>' });
  await flush();
  return h;
}

function assertReady(h, html) {
  assert.equal(h.pane.innerHTML, html);
  assert.equal(h.status.textContent, '최신 입력의 미리보기입니다.');
  assert.equal(h.pane.getAttribute('aria-busy'), 'false');
  assert.equal(h.button.textContent, '미리보기 갱신');
}

function assertStale(h) {
  assert.match(h.status.textContent, /이전 결과.*현재 입력과 다를 수 있습니다/);
}

test('entire inline application script parses', () => {
  assert.doesNotThrow(() => new vm.Script(script));
});

test('initial request uses the preview contract and announces loading and completion', async () => {
  const h = harness();
  const request = h.requests[0];
  assert.equal(request.url, '/api/preview');
  assert.equal(request.options.method, 'POST');
  assert.equal(request.options.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(request.options.body), { markdown: '# Initial', numberHeadings: false });
  assert.equal(request.options.signal.aborted, false);
  assert.equal(h.pane.getAttribute('aria-busy'), 'true');
  assert.match(h.status.textContent, /갱신 중/);
  request.succeed({ html: '<h1>Initial</h1>' });
  await flush();
  assertReady(h, '<h1>Initial</h1>');
});

test('typing restarts the 600ms debounce and sends only the latest input', async () => {
  const h = await ready();
  h.edit('A');
  assertStale(h);
  assert.match(h.status.className, /text-warning-emphasis/);
  h.advance(599);
  assert.equal(h.requests.length, 1);
  h.edit('B');
  h.advance(599);
  assert.equal(h.requests.length, 1);
  h.advance(1);
  assert.equal(h.requests.length, 2);
  assert.deepEqual(JSON.parse(h.requests[1].options.body), { markdown: 'B', numberHeadings: false });
  h.requests[1].succeed({ html: '<p>B</p>' });
  await flush();
  assertReady(h, '<p>B</p>');
});

test('input immediately invalidates a response arriving before the debounce expires', async () => {
  const h = harness();
  h.edit('New');
  assert.equal(h.requests[0].options.signal.aborted, true);
  h.requests[0].succeed({ html: '<p>Old</p>' });
  await flush();
  assert.equal(h.pane.innerHTML, '');
  assert.equal(h.pane.getAttribute('aria-busy'), 'true');
  assert.match(h.status.textContent, /갱신을 기다리고/);
  h.advance(600);
  h.requests[1].succeed({ html: '<p>New</p>' });
  await flush();
  assertReady(h, '<p>New</p>');
});

for (const outcome of ['success', 'network-error', 'invalid-shape', 'invalid-json']) {
  test(`older ${outcome} cannot overwrite a newer successful result even if abort is ignored`, async () => {
    const h = harness();
    h.element('editor').value = 'New';
    h.click();
    h.requests[1].succeed({ html: '<p>New</p>' });
    await flush();
    if (outcome === 'success') h.requests[0].succeed({ html: '<p>Old</p>' });
    if (outcome === 'network-error') h.requests[0].fail();
    if (outcome === 'invalid-shape') h.requests[0].succeed({ html: 42 });
    if (outcome === 'invalid-json') h.requests[0].headers(Promise.reject(new SyntaxError('Invalid JSON')));
    await flush();
    assertReady(h, '<p>New</p>');
  });
}

for (const outcome of ['success', 'failure']) {
  test(`older ${outcome} cannot clear the latest loading state or cancel its controller`, async () => {
    const h = harness();
    h.click();
    if (outcome === 'success') h.requests[0].succeed({ html: 'old' });
    else h.requests[0].fail();
    await flush();
    assert.equal(h.pane.getAttribute('aria-busy'), 'true');
    assert.match(h.status.textContent, /갱신 중/);
    assert.equal(h.button.textContent, '미리보기 갱신');
    h.edit('Newest');
    assert.equal(h.requests[1].options.signal.aborted, true);
    h.requests[1].succeed({ html: 'also old' });
    h.advance(600);
    h.requests[2].succeed({ html: 'newest' });
    await flush();
    assertReady(h, 'newest');
  });
}

test('a delayed older JSON body cannot overwrite the latest result', async () => {
  const h = harness();
  const body = deferred();
  h.requests[0].headers(body.promise);
  await flush();
  h.click();
  h.requests[1].succeed({ html: 'New' });
  await flush();
  body.resolve({ html: 'Old' });
  await flush();
  assertReady(h, 'New');
});

for (const outcome of ['success', 'failure']) {
  test(`older ${outcome} cannot overwrite the latest failure or its retry action`, async () => {
    const h = await ready();
    h.click();
    h.click();
    h.requests[2].fail(new Error('Latest request failed'));
    await flush();
    if (outcome === 'success') h.requests[1].succeed({ html: 'Old' });
    else h.requests[1].fail(new Error('Older request failed'));
    await flush();
    assert.equal(h.pane.innerHTML, '<p>Previous</p>');
    assert.match(h.status.textContent, /Latest request failed/);
    assert.doesNotMatch(h.status.textContent, /Older request failed/);
    assertStale(h);
    assert.equal(h.button.textContent, '미리보기 다시 시도');
    assert.equal(h.pane.getAttribute('aria-busy'), 'false');
  });
}

test('abort-aware requests are silently superseded without an error or retry state', async () => {
  const h = harness({ honorAbort: true });
  h.edit('New');
  await flush();
  assert.doesNotMatch(h.status.textContent, /오류/);
  assert.equal(h.pane.getAttribute('aria-busy'), 'true');
  h.advance(600);
  h.requests[1].succeed({ html: 'New' });
  await flush();
  assertReady(h, 'New');
});

test('a failure before any success shows an empty pane, an error and a working retry', async () => {
  const h = harness();
  h.requests[0].fail(new Error('Offline'));
  await flush();
  assert.equal(h.pane.innerHTML, '');
  assert.match(h.status.textContent, /Offline/);
  assert.doesNotMatch(h.status.textContent, /이전 결과/);
  assert.equal(h.pane.getAttribute('aria-busy'), 'false');
  assert.equal(h.button.textContent, '미리보기 다시 시도');
  h.click();
  assert.equal(h.requests[1].options.signal.aborted, false);
  h.requests[1].succeed({ html: 'Recovered' });
  await flush();
  assertReady(h, 'Recovered');
});

test('a latest failure preserves the previous result and marks it stale through retry', async () => {
  const h = await ready();
  h.element('editor').value = 'Changed';
  h.click();
  assertStale(h);
  h.requests[1].fail(new Error('<b>Offline</b>'));
  await flush();
  assert.equal(h.pane.innerHTML, '<p>Previous</p>');
  assertStale(h);
  assert.match(h.status.textContent, /<b>Offline<\/b>/);
  assert.match(h.status.className, /text-danger/);
  assert.equal(h.button.textContent, '미리보기 다시 시도');
  h.click();
  assertStale(h);
  assert.match(h.status.textContent, /갱신 중/);
  h.requests[2].succeed({ html: '<p>Changed</p>' });
  await flush();
  assertReady(h, '<p>Changed</p>');
});

for (const [index, data] of [null, [], 'html', 42, true, {}, { html: null }, { html: 1 }, { html: {} }, { html: [] }].entries()) {
  test(`invalid successful response shape ${index + 1} is rejected without replacing the previous HTML`, async () => {
    const h = await ready();
    h.click();
    h.requests[1].succeed(data);
    await flush();
    assert.equal(h.pane.innerHTML, '<p>Previous</p>');
    assert.match(h.status.textContent, /응답 형식이 올바르지 않습니다/);
    assertStale(h);
    assert.equal(h.pane.getAttribute('aria-busy'), 'false');
    assert.equal(h.button.textContent, '미리보기 다시 시도');
  });
}

test('empty Markdown and empty HTML are valid and remove the previous content', async () => {
  const h = await ready();
  h.edit('');
  h.advance(600);
  assert.equal(JSON.parse(h.requests[1].options.body).markdown, '');
  h.requests[1].succeed({ html: '' });
  await flush();
  assertReady(h, '');
});

for (const [index, data] of [null, {}, [], 'error', { error: {} }, { error: 1 }, { error: '' }, { error: ' ' }].entries()) {
  test(`HTTP failure body ${index + 1} falls back to the HTTP status instead of a misleading type error`, async () => {
    const h = harness();
    h.requests[0].succeed(data, 503);
    await flush();
    assert.match(h.status.textContent, /요청 실패 \(HTTP 503\)/);
    assert.equal(h.button.textContent, '미리보기 다시 시도');
    assert.equal(h.pane.getAttribute('aria-busy'), 'false');
  });
}

test('HTTP error messages remain visible as text', async () => {
  const h = harness();
  h.requests[0].succeed({ error: '<script>server error</script>' }, 400);
  await flush();
  assert.match(h.status.textContent, /<script>server error<\/script>/);
  assert.equal(h.status.innerHTML, '');
  assert.equal(h.pane.innerHTML, '');
});

for (const status of [200, 502]) {
  test(`malformed JSON for HTTP ${status} is reported with status and a retry action`, async () => {
    const h = harness();
    h.requests[0].headers(Promise.reject(new SyntaxError('Unexpected token')), status);
    await flush();
    assert.match(h.status.textContent, new RegExp('올바른 JSON.*HTTP ' + status));
    assert.equal(h.button.textContent, '미리보기 다시 시도');
    assert.equal(h.pane.getAttribute('aria-busy'), 'false');
  });
}

test('an unexpected cancellation of the current request is not silently left loading', async () => {
  const h = harness();
  h.requests[0].fail(new DOMException('Request cancelled', 'AbortError'));
  await flush();
  assert.match(h.status.textContent, /오류.*Request cancelled/);
  assert.equal(h.button.textContent, '미리보기 다시 시도');
  assert.equal(h.pane.getAttribute('aria-busy'), 'false');
});

test('manual refresh cancels the pending debounce without a duplicate request', async () => {
  const h = await ready();
  h.edit('New');
  h.advance(400);
  h.click();
  assert.equal(h.timers.size, 0);
  assert.equal(h.requests.length, 2);
  h.advance(1000);
  assert.equal(h.requests.length, 2);
  h.requests[1].succeed({ html: 'New' });
  await flush();
  assertReady(h, 'New');
});

test('numbering changes immediately refresh current text and cancel the debounce', async () => {
  const h = await ready();
  h.edit('## New');
  h.number(true);
  assert.equal(h.timers.size, 0);
  assert.deepEqual(JSON.parse(h.requests[1].options.body), { markdown: '## New', numberHeadings: true });
  h.requests[1].succeed({ html: '<h2>1. New</h2>' });
  await flush();
  assertReady(h, '<h2>1. New</h2>');
});

test('typing, numbering and manual refresh share one latest-only request lifecycle', async () => {
  const h = await ready();
  h.edit('B');
  h.number(true);
  h.edit('C');
  assert.equal(h.requests[1].options.signal.aborted, true);
  h.click();
  h.requests[1].succeed({ html: 'B' });
  await flush();
  assert.equal(h.pane.innerHTML, '<p>Previous</p>');
  assert.equal(h.pane.getAttribute('aria-busy'), 'true');
  assert.deepEqual(JSON.parse(h.requests[2].options.body), { markdown: 'C', numberHeadings: true });
  h.advance(1200);
  assert.equal(h.requests.length, 3);
  h.requests[2].succeed({ html: 'C' });
  await flush();
  assertReady(h, 'C');
});

test('an already queued obsolete timer cannot issue a request or clear the current timer', async () => {
  const h = await ready();
  h.edit('Old');
  const oldCallback = [...h.timers.values()][0].callback;
  h.edit('New');
  oldCallback();
  assert.equal(h.requests.length, 1);
  assert.equal(h.timers.size, 1);
  h.click();
  assert.equal(h.timers.size, 0);
  h.requests[1].succeed({ html: 'New' });
  await flush();
  assertReady(h, 'New');
});

test('preview status and option limitations are accessible and present in the actual markup', () => {
  assert.match(template, /id="preview-pane"[^>]*aria-describedby="preview-status preview-help"/);
  assert.match(template, /id="preview-status"[^>]*role="status"[^>]*aria-live="polite"/);
  assert.match(template, /입력 텍스트와 소제목 번호 옵션만 반영/);
  assert.match(template, /목차·이미지 포함·링크 처리·페이지 크기·출력 설정은 최종 PDF\/DOCX에만 적용/);
});
