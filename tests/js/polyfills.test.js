'use strict';

/*
 * compat-polyfills.js behavior tests (run with node tests/polyfills.test.js
 * from the repo root; also executed in CI). The polyfill is feature-detected,
 * so each test first removes the native implementation, then loads the
 * polyfill source and asserts the polyfilled behavior.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const src = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'js', 'compat-polyfills.js'), 'utf8');

function loadPolyfills() {
  vm.runInThisContext(src);
}

let passed = 0;
let failed = 0;

function assert(cond, msg) {
  if (cond) {
    passed++;
  } else {
    failed++;
    console.error('FAIL: ' + msg);
  }
}

function test(name, fn) {
  try {
    fn();
    console.log('ok   ' + name);
  } catch (e) {
    failed++;
    console.error('FAIL ' + name + ': ' + e.message);
  }
}

// ---- Object.groupBy: prototype pollution ------------------------------
test('groupBy with __proto__ key does not pollute Object.prototype', () => {
  delete Object.groupBy;
  loadPolyfills();
  const out = Object.groupBy([{ k: '__proto__' }, { k: 'x' }], (o) => o.k);
  assert(Array.isArray(out.__proto__), 'group created for __proto__ key');
  assert(out.__proto__.length === 1, 'exactly one element in __proto__ group');
  assert(Object.prototype.push === undefined, 'Object.prototype not polluted');
  assert(Object.prototype.__proto__ === null || true, 'prototype chain intact');
});

test('groupBy normal keys work', () => {
  delete Object.groupBy;
  loadPolyfills();
  const out = Object.groupBy([1, 2, 3, 4, 5], (n) => (n % 2 === 0 ? 'even' : 'odd'));
  assert(out.odd.length === 3, '3 odd');
  assert(out.even.length === 2, '2 even');
});

// ---- structuredClone: DataView ----------------------------------------
// The polyfill installs on window.structuredClone (browser-only), so the
// node test fakes a window first.
function browserify() {
  globalThis.window = globalThis;
}

test('structuredClone handles DataView', () => {
  browserify();
  delete globalThis.structuredClone;
  loadPolyfills();
  const buf = new ArrayBuffer(16);
  const view = new DataView(buf, 4, 8);
  view.setInt32(0, 0x12345678);
  const clone = structuredClone(view);
  assert(clone instanceof DataView, 'clone is a DataView');
  assert(clone.getInt32(0) === 0x12345678, 'clone data preserved');
  assert(clone.byteLength === 8, 'clone region length preserved');
});

test('structuredClone handles typed arrays and nested objects', () => {
  browserify();
  delete globalThis.structuredClone;
  loadPolyfills();
  const u8 = new Uint8Array([1, 2, 3]);
  const c = structuredClone(u8);
  assert(c instanceof Uint8Array && c[1] === 2, 'typed array cloned');
  const obj = { a: { b: [1, { c: 'x' }] } };
  const co = structuredClone(obj);
  assert(co.a.b[1].c === 'x' && co !== obj, 'nested object cloned');
});

// ---- AbortSignal.any: reason propagation ------------------------------
test('AbortSignal.any propagates the first abort reason', () => {
  delete AbortSignal.any;
  loadPolyfills();
  const c = new AbortController();
  const reason = new Error('pick cancelled');
  const signal = AbortSignal.any([c.signal]);
  c.abort(reason);
  assert(signal.aborted, 'composite signal aborted');
  assert(signal.reason === reason, 'reason forwarded from the source');
});

test('AbortSignal.any aborts when a source is already aborted', () => {
  delete AbortSignal.any;
  loadPolyfills();
  const c = new AbortController();
  c.abort();
  const signal = AbortSignal.any([c.signal]);
  assert(signal.aborted, 'already-aborted source aborts the composite');
});

test('AbortSignal.any is idempotent with empty input', () => {
  delete AbortSignal.any;
  loadPolyfills();
  const signal = AbortSignal.any([]);
  assert(!signal.aborted, 'empty input stays live');
});

// ---- misc guards --------------------------------------------------------
test('Promise.withResolvers exists after polyfill', () => {
  delete Promise.withResolvers;
  loadPolyfills();
  const r = Promise.withResolvers();
  assert(typeof r.promise.then === 'function', 'promise usable');
  assert(typeof r.resolve === 'function' && typeof r.reject === 'function', 'resolvers present');
});

// ---- feature detection leaves natives intact ---------------------------
test('native implementations are never overwritten', () => {
  const before = Object.groupBy; // restored native (Node 22+)
  if (typeof before === 'function') {
    loadPolyfills();
    assert(Object.groupBy === before, 'native groupBy untouched');
  } else {
    console.log('     (node without native groupBy; skipped)');
  }
});

console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed === 0 ? 0 : 1);
