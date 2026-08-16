/**
 * Old-WebView compatibility layer for the Harness front-end.
 *
 * Android 10 devices carry 2019-era system WebViews (Chromium ~78-80) while
 * the Harness UI is modern code. This file polyfills the runtime APIs the
 * front-end actually relies on, spanning 2019 → 2024 Chrome versions.
 * Injected before the page's own scripts run (onPageStarted); every polyfill
 * is guarded so modern WebViews skip it untouched.
 *
 * Not polyfillable (skipped deliberately): WeakRef/FinalizationRegistry
 * (need real weak references), off-main-thread rendering APIs, CSS features.
 */
(function () {
  'use strict';

  // ===== AbortSignal (Chrome 116+) =====
  if (typeof AbortSignal !== 'undefined') {
    if (!AbortSignal.any) {
      AbortSignal.any = function (signals) {
        var c = new AbortController();
        var abort = function () { c.abort(); };
        signals = signals || [];
        for (var i = 0; i < signals.length; i++) {
          if (signals[i].aborted) { abort(); break; }
          signals[i].addEventListener('abort', abort);
        }
        c.signal.addEventListener('abort', function () {
          for (var i = 0; i < signals.length; i++) {
            signals[i].removeEventListener('abort', abort);
          }
        });
        return c.signal;
      };
    }
    if (!AbortSignal.timeout) {
      AbortSignal.timeout = function (ms) {
        var c = new AbortController();
        setTimeout(function () {
          c.abort(new DOMException('The operation timed out.', 'TimeoutError'));
        }, ms);
        return c.signal;
      };
    }
    if (AbortSignal.prototype && !AbortSignal.prototype.throwIfAborted) {
      AbortSignal.prototype.throwIfAborted = function () {
        if (this.aborted) throw this.reason;
      };
    }
  }

  // ===== Promise (Chrome 85+ / 119+) =====
  if (typeof Promise !== 'undefined') {
    if (!Promise.any) {
      var AggregateErrorCtor =
        typeof AggregateError === 'function'
          ? AggregateError
          : (function () {
              function E(message) { this.message = message || 'All promises were rejected'; this.name = 'AggregateError'; this.errors = []; }
              E.prototype = Object.create(Error.prototype);
              E.prototype.constructor = E;
              return E;
            })();
      Promise.any = function (promises) {
        return new Promise(function (resolve, reject) {
          var arr = Array.from(promises || []);
          if (arr.length === 0) { reject(new AggregateErrorCtor()); return; }
          var errors = new Array(arr.length);
          var done = 0;
          arr.forEach(function (p, i) {
            Promise.resolve(p).then(resolve, function (e) {
              errors[i] = e;
              done++;
              if (done === arr.length) {
                var agg = new AggregateErrorCtor();
                agg.errors = errors;
                reject(agg);
              }
            });
          });
        });
      };
    }
    if (!Promise.withResolvers) {
      Promise.withResolvers = function () {
        var r = {};
        r.promise = new Promise(function (res, rej) { r.resolve = res; r.reject = rej; });
        return r;
      };
    }
  }

  // ===== structuredClone (Chrome 98+) =====
  if (typeof window !== 'undefined' && typeof window.structuredClone === 'undefined') {
    window.structuredClone = (function () {
      return function (value) {
        if (value === null || typeof value !== 'object') return value;
        var seen = new Map();
        function clone(v) {
          if (v === null || typeof v !== 'object') return v;
          if (seen.has(v)) return seen.get(v);
          var out;
          if (Array.isArray(v)) {
            out = [];
            seen.set(v, out);
            for (var i = 0; i < v.length; i++) out.push(clone(v[i]));
            return out;
          }
          if (v instanceof Date) return new Date(v.getTime());
          if (v instanceof RegExp) return new RegExp(v.source, v.flags);
          if (v instanceof Map) {
            out = new Map();
            seen.set(v, out);
            v.forEach(function (val, key) { out.set(clone(key), clone(val)); });
            return out;
          }
          if (v instanceof Set) {
            out = new Set();
            seen.set(v, out);
            v.forEach(function (val) { out.add(clone(val)); });
            return out;
          }
          if (v instanceof ArrayBuffer) return v.slice(0);
          if (ArrayBuffer.isView(v)) return new v.constructor(v);
          out = {};
          seen.set(v, out);
          Object.keys(v).forEach(function (k) { out[k] = clone(v[k]); });
          return out;
        }
        return clone(value);
      };
    })();
  }

  // ===== Object (Chrome 93+ / 117+) =====
  if (typeof Object.hasOwn === 'undefined') {
    Object.hasOwn = function (obj, key) { return Object.prototype.hasOwnProperty.call(obj, key); };
  }
  if (typeof Object.groupBy === 'undefined') {
    Object.groupBy = function (items, fn) {
      var out = {};
      items.forEach(function (item, i) {
        var k = String(fn(item, i));
        (out[k] || (out[k] = [])).push(item);
      });
      return out;
    };
  }
  if (typeof Map !== 'undefined' && typeof Map.groupBy === 'undefined') {
    Map.groupBy = function (items, fn) {
      var out = new Map();
      items.forEach(function (item, i) {
        var k = fn(item, i);
        if (!out.has(k)) out.set(k, []);
        out.get(k).push(item);
      });
      return out;
    };
  }

  // ===== Array (Chrome 92+ / 97+ / 110+) =====
  if (typeof Array.prototype.at === 'undefined') {
    Object.defineProperty(Array.prototype, 'at', {
      value: function (index) {
        var n = Number(index);
        var len = this.length;
        if (Number.isNaN(n)) n = 0;
        if (n < 0) n += len;
        return n >= 0 && n < len ? this[n] : undefined;
      },
      writable: true, configurable: true,
    });
  }
  if (typeof Array.prototype.findLast === 'undefined') {
    Object.defineProperty(Array.prototype, 'findLast', {
      value: function (fn, thisArg) {
        for (var i = this.length - 1; i >= 0; i--) {
          if (fn.call(thisArg, this[i], i, this)) return this[i];
        }
        return undefined;
      },
      writable: true, configurable: true,
    });
  }
  if (typeof Array.prototype.findLastIndex === 'undefined') {
    Object.defineProperty(Array.prototype, 'findLastIndex', {
      value: function (fn, thisArg) {
        for (var i = this.length - 1; i >= 0; i--) {
          if (fn.call(thisArg, this[i], i, this)) return i;
        }
        return -1;
      },
      writable: true, configurable: true,
    });
  }
  if (typeof Array.prototype.toSorted === 'undefined') {
    Object.defineProperty(Array.prototype, 'toSorted', {
      value: function (cmp) { return this.slice().sort(cmp); },
      writable: true, configurable: true,
    });
  }
  if (typeof Array.prototype.toReversed === 'undefined') {
    Object.defineProperty(Array.prototype, 'toReversed', {
      value: function () { return this.slice().reverse(); },
      writable: true, configurable: true,
    });
  }
  if (typeof Array.prototype.toSpliced === 'undefined') {
    Object.defineProperty(Array.prototype, 'toSpliced', {
      value: function (start, deleteCount) {
        var args = Array.prototype.slice.call(arguments, 2);
        var arr = this.slice();
        arr.splice.apply(arr, [start, deleteCount].concat(args));
        return arr;
      },
      writable: true, configurable: true,
    });
  }
  if (typeof Array.prototype.with === 'undefined') {
    Object.defineProperty(Array.prototype, 'with', {
      value: function (index, value) {
        var arr = this.slice();
        var i = Number(index);
        if (i < 0) i += arr.length;
        arr[i] = value;
        return arr;
      },
      writable: true, configurable: true,
    });
  }

  // ===== String (Chrome 85+ / 92+) =====
  if (typeof String.prototype.replaceAll === 'undefined') {
    String.prototype.replaceAll = function (search, replace) {
      if (search instanceof RegExp) {
        if (!search.global) throw new TypeError('String.prototype.replaceAll: non-global RegExp');
        return this.replace(search, replace);
      }
      return this.split(search).join(replace);
    };
  }
  if (typeof String.prototype.at === 'undefined') {
    Object.defineProperty(String.prototype, 'at', {
      value: Array.prototype.at,
      writable: true, configurable: true,
    });
  }

  // ===== Element (Chrome 86+; React 18 rendering relies on it) =====
  if (typeof Element !== 'undefined' && typeof Element.prototype.replaceChildren === 'undefined') {
    Element.prototype.replaceChildren = function () {
      while (this.firstChild) this.removeChild(this.firstChild);
      for (var i = 0; i < arguments.length; i++) {
        var v = arguments[i];
        this.appendChild(typeof v === 'string' ? document.createTextNode(v) : v);
      }
    };
  }

  // ===== crypto.randomUUID (Chrome 92+) =====
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'undefined') {
    crypto.randomUUID = function () {
      var b = crypto.getRandomValues(new Uint8Array(16));
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      var hex = Array.prototype.map.call(b, function (x) {
        return ('0' + x.toString(16)).slice(-2);
      });
      return hex[0] + hex[1] + hex[2] + hex[3] + '-' + hex[4] + hex[5] + '-' +
        hex[6] + hex[7] + '-' + hex[8] + hex[9] + '-' +
        hex[10] + hex[11] + hex[12] + hex[13] + hex[14] + hex[15];
    };
  }

  // ===== URL.canParse (Chrome 120+) =====
  if (typeof URL !== 'undefined' && !URL.canParse) {
    URL.canParse = function (url, base) {
      try { new URL(url, base); return true; } catch (e) { return false; }
    };
  }
})();
