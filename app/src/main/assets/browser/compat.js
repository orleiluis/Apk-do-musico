// Complementos para WebViews antigos (tablets Fire, celulares sem atualização do WebView).
// Sites novos, como a área da Kiwify, usam funções que esses WebViews ainda não têm e param
// na tela de "Loading...". Cada função só é criada quando falta; num WebView atual nada muda.
(function () {
  'use strict';
  function def(obj, name, fn) {
    if (obj && !(name in obj)) {
      try { Object.defineProperty(obj, name, { value: fn, writable: true, configurable: true }); } catch (e) {}
    }
  }
  var g = typeof globalThis !== 'undefined' ? globalThis : (typeof self !== 'undefined' ? self : window);
  if (typeof globalThis === 'undefined') { try { g.globalThis = g; } catch (e) {} }

  function at(i) {
    var n = Math.trunc(i) || 0;
    if (n < 0) n += this.length;
    return n < 0 || n >= this.length ? undefined : this[n];
  }
  def(Array.prototype, 'at', at);
  def(String.prototype, 'at', at);
  ['Int8Array', 'Uint8Array', 'Uint8ClampedArray', 'Int16Array', 'Uint16Array', 'Int32Array',
    'Uint32Array', 'Float32Array', 'Float64Array'].forEach(function (t) { if (g[t]) def(g[t].prototype, 'at', at); });

  def(Object, 'hasOwn', function (o, k) { return Object.prototype.hasOwnProperty.call(Object(o), k); });
  def(Object, 'fromEntries', function (it) {
    var o = {};
    Array.from(it).forEach(function (e) { o[e[0]] = e[1]; });
    return o;
  });
  def(Object, 'groupBy', function (items, fn) {
    var o = Object.create(null), i = 0;
    Array.from(items).forEach(function (v) { var k = fn(v, i++); (o[k] || (o[k] = [])).push(v); });
    return o;
  });
  if (g.Map) def(Map, 'groupBy', function (items, fn) {
    var m = new Map(), i = 0;
    Array.from(items).forEach(function (v) { var k = fn(v, i++); if (!m.has(k)) m.set(k, []); m.get(k).push(v); });
    return m;
  });

  def(Array.prototype, 'findLast', function (fn, self) {
    for (var i = this.length - 1; i >= 0; i--) if (fn.call(self, this[i], i, this)) return this[i];
    return undefined;
  });
  def(Array.prototype, 'findLastIndex', function (fn, self) {
    for (var i = this.length - 1; i >= 0; i--) if (fn.call(self, this[i], i, this)) return i;
    return -1;
  });
  def(Array.prototype, 'toSorted', function (cmp) { return Array.prototype.slice.call(this).sort(cmp); });
  def(Array.prototype, 'toReversed', function () { return Array.prototype.slice.call(this).reverse(); });
  def(Array.prototype, 'toSpliced', function () {
    var c = Array.prototype.slice.call(this);
    Array.prototype.splice.apply(c, arguments);
    return c;
  });
  def(Array.prototype, 'with', function (i, v) {
    var n = Math.trunc(i) || 0;
    if (n < 0) n += this.length;
    if (n < 0 || n >= this.length) throw new RangeError('Invalid index');
    var c = Array.prototype.slice.call(this);
    c[n] = v;
    return c;
  });
  def(Array.prototype, 'flat', function flat(depth) {
    var d = depth === undefined ? 1 : Math.trunc(depth) || 0;
    var out = [];
    (function walk(arr, lvl) {
      for (var i = 0; i < arr.length; i++) {
        if (!(i in arr)) continue;
        if (Array.isArray(arr[i]) && lvl > 0) walk(arr[i], lvl - 1); else out.push(arr[i]);
      }
    })(this, d);
    return out;
  });
  def(Array.prototype, 'flatMap', function (fn, self) { return Array.prototype.map.call(this, fn, self).flat(1); });

  def(String.prototype, 'replaceAll', function (search, rep) {
    if (search instanceof RegExp) {
      if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
      return this.replace(search, rep);
    }
    return this.split(String(search)).join(typeof rep === 'function' ? rep(String(search)) : rep);
  });
  def(String.prototype, 'isWellFormed', function () { return !/[\uD800-\uDFFF]/.test(this.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g, '')); });

  if (typeof Promise !== 'undefined') {
    def(Promise, 'allSettled', function (list) {
      return Promise.all(Array.from(list).map(function (p) {
        return Promise.resolve(p).then(
          function (value) { return { status: 'fulfilled', value: value }; },
          function (reason) { return { status: 'rejected', reason: reason }; });
      }));
    });
    def(Promise, 'any', function (list) {
      var arr = Array.from(list);
      return new Promise(function (resolve, reject) {
        var errors = [], left = arr.length;
        if (!left) reject(new Error('All promises were rejected'));
        arr.forEach(function (p, i) {
          Promise.resolve(p).then(resolve, function (e) {
            errors[i] = e;
            if (--left === 0) { var err = new Error('All promises were rejected'); err.errors = errors; reject(err); }
          });
        });
      });
    });
    def(Promise, 'withResolvers', function () {
      var r = {};
      r.promise = new Promise(function (a, b) { r.resolve = a; r.reject = b; });
      return r;
    });
  }

  if (typeof g.queueMicrotask !== 'function') {
    g.queueMicrotask = function (fn) { Promise.resolve().then(fn); };
  }
  if (typeof g.structuredClone !== 'function') {
    g.structuredClone = function (v) {
      if (v === undefined) return undefined;
      return JSON.parse(JSON.stringify(v));
    };
  }
  if (g.crypto && typeof g.crypto.randomUUID !== 'function' && g.crypto.getRandomValues) {
    try {
      g.crypto.randomUUID = function () {
        var b = g.crypto.getRandomValues(new Uint8Array(16));
        b[6] = (b[6] & 0x0f) | 0x40;
        b[8] = (b[8] & 0x3f) | 0x80;
        var h = Array.prototype.map.call(b, function (x) { return (x + 0x100).toString(16).slice(1); }).join('');
        return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
      };
    } catch (e) {}
  }
  if (g.AbortSignal) {
    def(AbortSignal, 'timeout', function (ms) {
      var c = new AbortController();
      setTimeout(function () { c.abort(); }, ms);
      return c.signal;
    });
    def(AbortSignal, 'any', function (signals) {
      var c = new AbortController();
      Array.from(signals).forEach(function (s) {
        if (s.aborted) c.abort(); else s.addEventListener('abort', function () { c.abort(); });
      });
      return c.signal;
    });
  }
  if (typeof g.requestIdleCallback !== 'function') {
    g.requestIdleCallback = function (fn) {
      var start = Date.now();
      return setTimeout(function () { fn({ didTimeout: false, timeRemaining: function () { return Math.max(0, 50 - (Date.now() - start)); } }); }, 1);
    };
    g.cancelIdleCallback = function (id) { clearTimeout(id); };
  }
})();
