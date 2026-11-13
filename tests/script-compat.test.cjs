'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const { parse } = require('acorn');
const Babel = require('@babel/standalone');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/js/compat/compiler.js'), 'utf8');
const sandbox = { Babel, module: { exports: {} } };
vm.runInNewContext(source, sandbox);
const compiler = sandbox.module.exports;
const polyfill = fs.readFileSync(path.join(__dirname, '../app/build/generated/webviewAssets/compat/polyfills.js'), 'utf8');

test('modern engines pass the same syntax probe used before official navigation', () => {
  assert.equal(compiler.supportsPlayerSyntax(), true);
  const old = { module: { exports: {} }, Function: function () { throw new SyntaxError(); } };
  vm.runInNewContext(source, old);
  assert.equal(old.module.exports.supportsPlayerSyntax(), false);
});

test('private method/accessor translation retains state, this, inheritance and logical assignments', () => {
  const fixture = `
    class Base { read() { return 10; } }
    class Player extends Base {
      #value = 1;
      #add(n) { this.#value += n; }
      get #current() { return this.#value; }
      set #current(n) { this.#value = n; }
      run() { this.#add(2); this.#current = this.#current + super.read(); return this.#current; }
    }
    var a, b = 0, c = 1, calls = 0;
    a ??= (++calls); b ||= (++calls); c &&= (++calls);
    globalThis.result = [new Player().run(), a, b, c, calls];
  `;
  const translated = compiler.transform(fixture, 83);
  // Chrome 83 supports ES2020's ?? but rejects private methods and ??=.
  assert.throws(() => parse(fixture, { ecmaVersion: 2019 }));
  parse(translated, { ecmaVersion: 2020 });
  const original = {}, legacy = {};
  vm.runInNewContext(fixture, original);
  vm.runInNewContext(translated, legacy);
  assert.equal(JSON.stringify(legacy.result), JSON.stringify(original.result));
  assert.equal(JSON.stringify(legacy.result), '[13,1,2,3,3]');
});

test('compiler parses text without invoking official code or injecting polyfills', () => {
  const translated = compiler.transform('throw new Error("must not execute"); class A { #f() {} }', 83);
  assert.ok(translated.includes('must not execute'));
  assert.ok(!translated.includes('core-js'));
  assert.ok(!translated.includes('require('));
  assert.throws(() => compiler.transform('class { invalid(', 83));
});

test('module scripts retain imports and exports at their original URL', () => {
  const translated = compiler.transform('import value from "./dependency.js"; export class A { #read() { return value; } read() { return this.#read(); } }', 83, polyfill);
  parse(translated, { ecmaVersion: 2020, sourceType: 'module' });
  assert.ok(translated.includes('./dependency.js'));
  assert.ok(translated.includes('export'));
  assert.ok(!translated.includes('require('));
});

test('the combined response preserves strict directives and installs the patch before official code', () => {
  const fixture = `#!/usr/bin/env node
    "official directive"; "use strict";
    globalThis.strictThis = (function() { return this; })();
    try { accidentalGlobal = 1; } catch (error) { globalThis.errorName = error.name; }
    globalThis.result = "aba".replaceAll("a", "x");
    class A { #read() { return 7; } read() { return this.#read(); } }
    globalThis.value = new A().read();
  `;
  const translated = compiler.transform(fixture, 83, polyfill);
  const ast = parse(translated, { ecmaVersion: 2020, allowHashBang: true });
  assert.equal(ast.body[0].directive, 'official directive');
  assert.equal(ast.body[1].directive, 'use strict');
  const legacy = vm.createContext({});
  vm.runInContext('delete String.prototype.replaceAll', legacy);
  vm.runInContext(translated, legacy);
  assert.equal(legacy.strictThis, undefined);
  assert.equal(legacy.errorName, 'ReferenceError');
  assert.equal(legacy.result, 'xbx');
  assert.equal(legacy.value, 7);
  assert.equal('accidentalGlobal' in legacy, false);
});

test('the combined response preserves sloppy script behavior', () => {
  const translated = compiler.transform(`
    globalThis.sloppyThis = (function() { return this === globalThis; })();
    accidentalGlobal = 1;
    globalThis.result = "aba".replaceAll("a", "x");
  `, 83, polyfill);
  const legacy = vm.createContext({});
  vm.runInContext('delete String.prototype.replaceAll', legacy);
  vm.runInContext(translated, legacy);
  assert.equal(legacy.sloppyThis, true);
  assert.equal(legacy.accidentalGlobal, 1);
  assert.equal(legacy.result, 'xbx');
});

test('local compiler protocol handles chunks and validates before returning results', () => {
  let ready = false, complete, result = '';
  const fixture = '"use strict"; class A { #f() { return 7; } read() { return this.#f(); } }';
  const local = {
    Babel, window: {},
    compiler: {
      polyfills() { return polyfill; },
      ready(ok) { ready = ok; },
      input(id, index) { assert.equal(id, 'job'); return index === 0 ? fixture : ''; },
      output(id, part) { assert.equal(id, 'job'); result += part; },
      finish(id, ok) { assert.equal(id, 'job'); complete = ok; }
    }
  };
  vm.runInNewContext(source, local);
  assert.equal(ready, true);
  local.window.bahaCompile('job', 83);
  assert.equal(complete, true);
  assert.equal(parse(result, { ecmaVersion: 2019 }).body[0].directive, 'use strict');
});

test('legacy prefix fills String.replaceAll with native replacement semantics and is idempotent', () => {
  parse(polyfill, { ecmaVersion: 2019 });
  const legacy = vm.createContext({});
  vm.runInContext('delete String.prototype.replaceAll', legacy);
  vm.runInContext(polyfill, legacy);
  for (const expression of [
    '"aba".replaceAll("a", "x")',
    '"aba".replaceAll("", "-")',
    '"abcabc".replaceAll("a", "$&:$`:$\\u0027:$$:$1")',
    '"a.b.a".replaceAll(".", "*")',
    '"aaaa".replaceAll("aa", function(match, offset) { return offset; })',
    '"abac".replaceAll(/a/g, "x")',
    '"😀".replaceAll("", ".")'
  ]) assert.equal(vm.runInContext(expression, legacy), vm.runInNewContext(expression));
  assert.equal(vm.runInContext('Object.getOwnPropertyDescriptor(String.prototype,"replaceAll").enumerable', legacy), false);
  assert.throws(() => vm.runInContext('"a".replaceAll(/a/, "b")', legacy), { name: 'TypeError' });
  assert.throws(() => vm.runInContext('String.prototype.replaceAll.call(null,"a","b")', legacy), { name: 'TypeError' });
  vm.runInContext('globalThis.firstReplaceAll = String.prototype.replaceAll', legacy);
  vm.runInContext(polyfill, legacy);
  assert.equal(vm.runInContext('firstReplaceAll === String.prototype.replaceAll', legacy), true);
  const modern = vm.createContext({});
  vm.runInContext('globalThis.firstReplaceAll = String.prototype.replaceAll', modern);
  vm.runInContext(polyfill, modern);
  assert.equal(vm.runInContext('firstReplaceAll === String.prototype.replaceAll', modern), true);
});
