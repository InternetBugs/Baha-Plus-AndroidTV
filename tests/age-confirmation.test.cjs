'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../app/src/main/assets/adapters/age-confirmation.js'), 'utf8');

function page(origin = 'https://ani.gamer.com.tw') {
  let clicks = 0, current = true;
  const element = () => ({
    disabled: false, textContent: '同意',
    style: {display: 'block', visibility: 'visible', opacity: '1'},
    getAttribute: () => null,
    getBoundingClientRect: () => ({width: 120, height: 48}),
  });
  const agree = {...element(), click() { clicks++; }};
  const prompt = {...element(), querySelector(selector) {
    assert.equal(selector, 'button#adult.choose-btn-agree');
    return agree;
  }};
  const state = {matches: true, phase: 'age', root: {querySelector(selector) {
    assert.equal(selector, '.ncc-choosebar');
    return prompt;
  }}};
  const sandbox = {location: {origin}, getComputedStyle: node => node.style};
  sandbox.window = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(source, context);
  const bridge = context.__bahaCreateAgeConfirmation?.({context: () => state, isCurrent: () => current});
  return {context, bridge, agree, prompt, state, clicks: () => clicks, expire() { current = false; }};
}

test('age shortcut requires an explicit action and clicks the real official agreement once', () => {
  const p = page();
  assert.equal(p.bridge.available(), true);
  assert.equal(p.bridge.available(), true);
  assert.equal(p.clicks(), 0);
  assert.equal(p.bridge.confirm(), true);
  assert.equal(p.clicks(), 1);
  assert.equal(p.bridge.confirm(), false);
  assert.equal(p.bridge.available(), false);
  assert.equal(p.clicks(), 1);
});

test('age shortcut refuses old documents, suspended sessions and other playback phases', () => {
  const p = page(); p.expire();
  assert.equal(p.bridge.confirm(), false);
  for (const phase of ['loading', 'advertisement', 'content', 'official', 'error']) {
    const q = page(); q.state.phase = phase;
    assert.equal(q.bridge.available(), false);
    assert.equal(q.bridge.confirm(), false);
    assert.equal(q.clicks(), 0);
  }
  const suspended = page(); suspended.context.__bahaMediaSuspended = true;
  assert.equal(suspended.bridge.confirm(), false);
  const wrongSeries = page(); wrongSeries.state.matches = false;
  assert.equal(wrongSeries.bridge.confirm(), false);
  assert.equal(p.clicks() + suspended.clicks() + wrongSeries.clicks(), 0);
});

test('age shortcut refuses missing, disabled, hidden or changed official controls', () => {
  for (const mutate of [
    p => p.state.root = null,
    p => p.prompt.querySelector = () => null,
    p => p.agree.disabled = true,
    p => p.agree.getAttribute = () => 'true',
    p => p.agree.style.visibility = 'hidden',
    p => p.prompt.style.display = 'none',
    p => p.agree.style.opacity = '0',
    p => p.agree.getBoundingClientRect = () => ({width: 0, height: 48}),
    p => p.agree.textContent = '不同意',
  ]) {
    const p = page(); mutate(p);
    assert.equal(p.bridge.available(), false);
    assert.equal(p.bridge.confirm(), false);
    assert.equal(p.clicks(), 0);
  }
});

test('a later official age prompt can be confirmed, and a throwing handler remains retryable', () => {
  const p = page();
  p.agree.click = () => { throw Error('Official handler unavailable'); };
  assert.equal(p.bridge.confirm(), false);
  assert.equal(p.bridge.available(), true);
  p.agree.click = () => {};
  assert.equal(p.bridge.confirm(), true);
  p.state.phase = 'advertisement';
  assert.equal(p.bridge.available(), false);
  p.state.phase = 'age';
  assert.equal(p.bridge.available(), true);
  assert.equal(p.bridge.confirm(), true);
});

test('age shortcut is never installed on an unrelated origin', () => {
  assert.equal(page('https://ani.gamer.com.tw.attacker.invalid').bridge, undefined);
});
