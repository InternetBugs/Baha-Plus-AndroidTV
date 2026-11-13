'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(process.env.BAHA_ADAPTERS_DIR ||
  path.join(__dirname, '../app/src/main/js/adapters'), 'login-session.js'), 'utf8');

function page(id = 'guest', origin = 'https://ani.gamer.com.tw') {
  const messages = [], callbacks = new Set(), listeners = new Map();
  const context = vm.createContext({
    location:{origin}, User:{Login:{getUserid:() => id}}, __bahaDocumentNonce:'one',
    document:{querySelectorAll:() => []},
    setInterval(fn) { callbacks.add(fn); return fn; }, clearInterval(fn) { callbacks.delete(fn); },
    addEventListener(name, fn) { listeners.set(name, fn); },
    removeEventListener(name) { listeners.delete(name); },
    bahaPostJSON(raw) { messages.push(JSON.parse(raw)); }
  });
  context.window = context;
  vm.runInContext(source, context);
  return {context, messages, callbacks, listeners};
}
test('web login signals completion once and never transfers account data', () => {
  const p = page('owner');
  assert.deepEqual(p.messages, [{type:'login-complete'}]);
  assert.equal(p.callbacks.size, 0);
  assert.equal(p.listeners.size, 0);
});
test('guest waits for official identity; stale documents dispose the watcher', () => {
  const p = page();
  assert.equal(p.messages.length, 0);
  p.context.__bahaDocumentNonce = 'two';
  [...p.callbacks][0]();
  assert.equal(p.callbacks.size, 0);
  assert.equal(p.messages.length, 0);
});
test('untrusted origins do not create login watchers', () => {
  const p = page('owner', 'https://ani.gamer.com.tw.evil.invalid');
  assert.equal(p.messages.length, 0);
  assert.equal(p.callbacks.size, 0);
});
