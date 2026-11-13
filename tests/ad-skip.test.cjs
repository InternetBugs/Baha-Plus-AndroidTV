'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');
const source = readFileSync(join(process.env.BAHA_ADAPTERS_DIR || join(__dirname, '../app/src/main/js/adapters'), 'ad-skip.js'), 'utf8');

function page(origin = 'https://ani.gamer.com.tw') {
  let clicks = 0, current = true;
  const attributes = {}, classes = new Set();
  const node = {
    textContent: '廣告 5 秒，如何消除廣告？', disabled: false,
    style: {display: 'block', visibility: 'visible', opacity: '1', pointerEvents: 'auto'},
    getAttribute: name => attributes[name] ?? null,
    classList: {contains: value => classes.has(value)},
    getBoundingClientRect: () => ({width: 180, height: 48}),
    click: () => { clicks++; }
  };
  const state = {matches: true, phase: 'advertisement', root: {querySelector(selector) {
    assert.equal(selector, '.vast-skip-button');
    return node;
  }}};
  const sandbox = {location: {origin}, getComputedStyle: value => value.style};
  sandbox.window = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(source, context);
  const bridge = context.__bahaCreateAdSkip?.({context: () => state, isCurrent: () => current});
  return {context, bridge, node, state, attributes, classes, clicks: () => clicks, expire() { current = false; }};
}

test('countdown and subscription labels cannot trigger the official skip element', () => {
  const p = page();
  for (const label of ['廣告 5 秒，如何消除廣告？', '5 秒後可跳過廣告', '如何消除廣告？', 'Skip ad in 3', '']) {
    p.node.textContent = label;
    assert.equal(p.bridge.status().visible, true);
    assert.equal(p.bridge.status().available, false);
    assert.equal(p.bridge.skip(), false);
  }
  assert.equal(p.clicks(), 0);
});

test('official ready state enables a single explicit click without automatically skipping', () => {
  for (const label of ['點此跳過廣告', '跳過廣告', '跳过广告', '点击跳过广告', 'Skip Ad']) {
    const p = page(); p.node.textContent = label;
    assert.equal(p.bridge.status().available, true);
    assert.equal(p.clicks(), 0);
    assert.equal(p.bridge.skip(), true);
    assert.equal(p.clicks(), 1);
    assert.equal(p.bridge.status().visible, false);
    assert.equal(p.bridge.skip(), false);
    assert.equal(p.clicks(), 1);
  }
});

test('countdown status exposes only seconds, without subscription text', () => {
  const p = page();
  for (const [label, seconds] of [['廣告 5 秒，如何消除廣告？', 5], ['广告 12 秒，如何消除广告？', 12]]) {
    p.node.textContent = label;
    const status = p.bridge.status();
    assert.equal(status.remainingSeconds, seconds);
    assert.equal(status.label, undefined);
    assert.equal(JSON.stringify(status).includes('消除'), false);
  }
  p.node.textContent = '點此跳過廣告';
  assert.equal(p.bridge.status().remainingSeconds, null);
});

test('skip readiness is checked again at activation and resets for a later ad using the same element', () => {
  const p = page(); p.node.textContent = '點此跳過廣告';
  assert.equal(p.bridge.status().available, true);
  p.node.textContent = '廣告 4 秒，如何消除廣告？';
  assert.equal(p.bridge.skip(), false);
  p.node.textContent = '點此跳過廣告';
  assert.equal(p.bridge.skip(), true);
  p.node.textContent = '廣告 5 秒，如何消除廣告？';
  assert.equal(p.bridge.status().visible, true);
  assert.equal(p.bridge.status().available, false);
  p.node.textContent = '點此跳過廣告';
  assert.equal(p.bridge.skip(), true);
  assert.equal(p.clicks(), 2);
});

test('hidden, disabled, noninteractive or missing official skip controls cannot be activated', () => {
  for (const mutate of [
    p => p.state.root = null,
    p => p.state.root.querySelector = () => null,
    p => p.node.disabled = true,
    p => p.attributes.disabled = '',
    p => p.attributes['aria-disabled'] = 'true',
    p => p.classes.add('disabled'),
    p => p.classes.add('vjs-disabled'),
    p => p.node.style.pointerEvents = 'none',
    p => p.node.style.opacity = '0',
    p => p.node.style.display = 'none',
    p => p.node.style.visibility = 'hidden',
    p => p.node.getBoundingClientRect = () => ({width: 0, height: 48}),
  ]) {
    const p = page(); p.node.textContent = '點此跳過廣告'; mutate(p);
    assert.equal(p.bridge.status().available, false);
    assert.equal(p.bridge.skip(), false);
    assert.equal(p.clicks(), 0);
  }
});

test('skip refuses expired documents, other series, background and all non-ad phases', () => {
  for (const mutate of [
    p => p.expire(),
    p => p.context.__bahaMediaSuspended = true,
    p => p.state.matches = false,
    ...['loading', 'age', 'content', 'official', 'error'].map(phase => p => p.state.phase = phase),
  ]) {
    const p = page(); p.node.textContent = '點此跳過廣告'; mutate(p);
    assert.equal(p.bridge.status().visible, false);
    assert.equal(p.bridge.skip(), false);
    assert.equal(p.clicks(), 0);
  }
});

test('a throwing official skip handler remains retryable', () => {
  const p = page(); p.node.textContent = '點此跳過廣告';
  p.node.click = () => { throw Error('Official handler unavailable'); };
  assert.equal(p.bridge.skip(), false);
  assert.equal(p.bridge.status().available, true);
});

test('skip adapter is never installed on an unrelated origin', () => {
  assert.equal(page('https://ani.gamer.com.tw.attacker.invalid').bridge, undefined);
});
