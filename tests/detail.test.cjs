'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

const adapters = process.env.BAHA_ADAPTERS_DIR || join(__dirname, '../app/src/main/js/adapters');

function detailPage(options = {}) {
  const messages = [], requests = [];
  const text = textContent => ({ textContent });
  const link = (href, label, season = true, declared = null) => ({
    textContent: label,
    getAttribute: name => ({ href, 'data-ani-video-sn': declared })[name] ?? null,
    closest: () => season ? {} : null
  });
  const intro = {
    textContent: '官方簡介 ＜製作團隊＞ STAFF',
    querySelectorAll: () => [],
    cloneNode() { return this; }
  };
  const selectors = {
    '.anime_name button[onclick*="toggleGather"]': {
      getAttribute: () => `toggleGather(1, ${options.seriesId || '42'})`
    },
    '.anime_name h1': text('測試動畫 [1]'),
    '.data-intro': intro,
    '.data-img': { getAttribute: name => name === 'src' ? 'https://p2.bahamut.com.tw/poster.jpg' : null },
    '#acg_review .score-overall-number': text('4.5'),
    '#acg_review .score-overall-people': text('1,234,567 人評分')
  };
  const episodes = [
    link('?sn=100', '第 1 集', true, '100'),
    link('?sn=101', '第 2 集'),
    link('/animeVideo.php?sn=101', '重複集數'),
    link('/animeVideo.php?sn=102', '特別篇'),
    link('/animeVideo.php?sn=103', '3', false),
    link('/animeVideo.php?sn=104', '其他作品', false),
    link('https://attacker.invalid/animeVideo.php?sn=105', '5'),
    link('/animeVideo.php?sn=106', '6', true, '999'),
    link('/animeVideo.php?sn=invalid', '7')
  ];
  const document = {
    querySelector: selector => selectors[selector] || null,
    querySelectorAll: selector => {
      if (selector === 'script') return options.noRating ? [] : [
        text('new Bahamut.AcgReview("anime", 42, {"1":1,"2":2,"3":3,"4":4,"5":1234557})')
      ];
      if (selector.includes('.season a[href]')) return episodes;
      return [];
    },
    addEventListener() {}, removeEventListener() {},
    documentElement: { classList: { toggle() {}, remove() {} } }
  };
  const sandbox = {
    location: { origin: 'https://ani.gamer.com.tw', href: 'https://ani.gamer.com.tw/animeVideo.php?sn=100' },
    document, URL, AbortController, setTimeout, clearTimeout,
    setInterval: () => 1, clearInterval() {},
    navigator: {},
    __bahaDocumentNonce: 'current-document',
    __bahaNativeBackend: 'android-exoplayer',
    DOMParser: class { parseFromString() { return document; } },
    bahaPostJSON: raw => messages.push(JSON.parse(raw)),
    fetch: async (url, init) => {
      requests.push({ url, init });
      if (options.networkError) throw new TypeError('Network unavailable');
      return {
        ok: options.ok !== false,
        url: options.responseUrl || 'https://ani.gamer.com.tw/animeVideo.php?sn=100',
        text: async () => '<!doctype html>'
      };
    }
  };
  sandbox.window = sandbox;
  const context = vm.createContext(sandbox);
  // The reporting TV runs WebView 83. Do not borrow Node's newer String API.
  vm.runInContext('String.prototype.replaceAll = undefined;', context);
  return {
    context, messages, requests,
    async load(name) {
      const source = readFileSync(join(adapters, name + '.js'), 'utf8').replace('%ANIME_ID%', '42');
      vm.runInContext(source, context);
      await new Promise(setImmediate);
    }
  };
}

function verifyDetail(message, type) {
  assert.equal(message.type, type);
  assert.equal(message.id, '42');
  assert.equal(message.detail.title, '測試動畫');
  assert.equal(message.detail.description, '官方簡介');
  assert.equal(message.detail.rating.count, 1234567);
  assert.deepEqual(message.detail.episodes, [
    { id: '100', label: '第 1 集' },
    { id: '101', label: '第 2 集' },
    { id: '102', label: '特別篇' },
    { id: '103', label: '3' }
  ]);
}

test('detail loads episodes and grouped rating counts without String.replaceAll', async () => {
  const p = detailPage();
  await p.load('detail');
  assert.equal(p.messages.length, 1);
  verifyDetail(p.messages[0], 'detail');
  assert.equal(p.requests.length, 1);
  assert.equal(p.requests[0].url, '/animeRef.php?sn=42');
  assert.equal(p.requests[0].init.credentials, 'same-origin');
});

test('player publishes episodes and keeps polling without String.replaceAll', async () => {
  const p = detailPage();
  await p.load('player');
  verifyDetail(p.messages.find(message => message.type === 'player-detail'), 'player-detail');
  assert.ok(p.messages.some(message => message.type === 'player-state'));
  assert.equal(p.context.__bahaPlayerBridge.ticket, 'current-document');
  assert.equal(p.requests.length, 0);
  p.context.__bahaPlayerBridge.dispose();
});

test('a missing official rating does not prevent episode selection', async () => {
  const p = detailPage({ noRating: true });
  await p.load('detail');
  assert.equal(p.messages[0].type, 'detail');
  assert.equal(p.messages[0].detail.episodes.length, 4);
  assert.equal(p.messages[0].detail.rating, undefined);
});

test('detail still rejects failed requests, redirects and mismatched series', async () => {
  for (const options of [
    { networkError: true },
    { ok: false },
    { responseUrl: 'https://attacker.invalid/animeVideo.php?sn=100' },
    { responseUrl: 'https://ani.gamer.com.tw/login.php' },
    { seriesId: '99' }
  ]) {
    const p = detailPage(options);
    await p.load('detail');
    assert.deepEqual(p.messages, [{ type: 'detail-error', id: '42' }]);
  }
});
