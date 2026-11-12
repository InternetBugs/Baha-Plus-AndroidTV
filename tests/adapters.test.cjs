'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');
const adapters = join(__dirname, '../app/src/main/assets/adapters');
const source = name => readFileSync(join(adapters, name + '.js'), 'utf8');

function page(options = {}) {
  const messages = [], requests = [];
  const location = { origin: options.origin || 'https://ani.gamer.com.tw',
    href: 'https://ani.gamer.com.tw/animeVideo.php?sn=100' };
  const sandbox = {
    location, URL, AbortController, setTimeout, clearTimeout, Map, Set,
    document: { querySelector: () => null, querySelectorAll: () => [],
      body: { replaceChildren() {} } },
    bahaPostJSON: raw => messages.push(JSON.parse(raw)),
    fetch: async (url, init) => {
      requests.push({ url, init });
      return options.fetch ? options.fetch(url, init) :
        {ok: true, url, json: async () => ({data: {animeList: []}})};
    }
  };
  sandbox.window = sandbox;
  const context = vm.createContext(sandbox);
  return { context, messages, requests, load: name => vm.runInContext(source(name), context) };
}

test('TV commands never install outside the official origin', () => {
  const p = page({origin: 'https://ani.gamer.com.tw.attacker.invalid'});
  p.load('tv-commands');
  assert.equal(p.context.__bahaTVCommands, undefined);
  assert.equal(p.requests.length, 0);
});
test('invalid search input fails before making requests', async () => {
  for (const queries of [[], [''], ['a', 'b', 'c'], ['😀'.repeat(101)], [null]]) {
    const p = page(); p.load('tv-commands');
    await p.context.__bahaTVCommands.search(queries);
    assert.equal(p.messages[0].type, 'search-error');
    assert.equal(p.requests.length, 0);
  }
});
test('simplified/traditional search merges official records and keeps credentials in WebView', async () => {
  let count = 0;
  const p = page({fetch: async url => ({
    ok: true, url,
    json: async () => ({data: {animeList: [
      {animeSn: '42', videoSn: '100', title: '動畫', cover: 'https://p2.bahamut.com.tw/a.jpg', totalEpisode: 12},
      {animeSn: String(50 + count++), videoSn: '101', title: '第二部', cover: 'https://p2.bahamut.com.tw/b.jpg'}
    ]}})
  })});
  p.load('tv-commands');
  await p.context.__bahaTVCommands.search(['动画', '動畫']);
  assert.equal(p.messages[0].type, 'search');
  assert.equal(p.messages[0].items.length, 3);
  assert.equal(p.messages[0].items[0].episodeLabel, '12 集');
  assert.equal(p.requests.length, 2);
  assert.ok(p.requests.every(item => item.init.credentials === 'include'));
  assert.equal(new URL(p.requests[0].url).searchParams.get('kw'), '动画');
});
test('search rejects an off-origin response redirect', async () => {
  const p = page({fetch: async () => ({ok: true, url: 'https://example.invalid/anime/v1/search.php'})});
  p.load('tv-commands');
  await p.context.__bahaTVCommands.search(['test']);
  assert.equal(p.messages[0].type, 'search-error');
});
test('search rejects untrusted artwork and oversized payloads', async () => {
  for (const cover of ['http://p2.bahamut.com.tw/a.jpg', 'https://p2.bahamut.com.tw.attacker.invalid/a.jpg',
    'https://user:password@p2.bahamut.com.tw/a.jpg', 'https://p2.bahamut.com.tw:444/a.jpg']) {
    const p = page({fetch: async url => ({ok: true, url, json: async () => ({data: {animeList: [
      {animeSn: '42', videoSn: '100', title: '動畫', cover}
    ]}})})});
    p.load('tv-commands');
    await p.context.__bahaTVCommands.search(['test']);
    assert.equal(p.messages[0].type, 'search-error');
  }
  const p = page({fetch: async url => ({ok: true, url, json: async () => ({data: {animeList: Array(5001).fill({})}})})});
  p.load('tv-commands');
  await p.context.__bahaTVCommands.search(['test']);
  assert.equal(p.messages[0].type, 'search-error');
});
test('expired guest session cannot report a successful favorite change', async () => {
  const p = page(); p.load('tv-commands');
  await p.context.__bahaTVCommands.favorite('42', true);
  assert.equal(p.messages[0].type, 'account-guest');
  assert.equal(p.requests.length, 0);
});
test('favorite identifiers cannot inject code or HTML', async () => {
  const p = page(); p.load('tv-commands');
  await p.context.__bahaTVCommands.favorite("42');alert(1)//", true);
  assert.equal(p.messages[0].type, 'favorite-error');
  assert.equal(p.requests.length, 0);
});
test('HLS variants remain on the authorized HTTPS CDN and deduplicate heights', () => {
  const p = page(); p.load('native-hls');
  const values = p.context.__bahaParseHlsVariants(`#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720
720/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2000,RESOLUTION=1280x720
duplicate.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=3000,RESOLUTION=1920x1080
https://attacker.invalid/content.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4000,RESOLUTION=640x360
http://bahamut.akamaized.net/content.m3u8
`, 'https://bahamut.akamaized.net/a/master.m3u8');
  assert.equal(values.size, 1);
  assert.equal(values.get(720), 'https://bahamut.akamaized.net/a/720/index.m3u8');
});
test('HLS external audio groups are not exposed as self-contained video variants', () => {
  const p = page(); p.load('native-hls');
  const values = p.context.__bahaParseHlsVariants(`#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720,AUDIO="a"
video.m3u8`, 'https://bahamut.akamaized.net/master.m3u8');
  assert.equal(values.size, 0);
});

function nativePage(authorized = true) {
  const p = page({fetch: async url => ({
    ok: true, url, text: async () => '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\n720.m3u8'
  })});
  const events = [], removed = [];
  const media = { duration: 120, currentTime: 42, playbackRate: 1, muted: false, paused: false,
    currentSrc: 'https://bahamut.akamaized.net/master.m3u8',
    addEventListener() {}, removeEventListener() {}, pause() { this.paused = true; },
    removeAttribute(name) { removed.push(name); }, querySelectorAll: () => [], load() {} };
  const player = { currentSource: () => ({src: media.currentSrc}), isDisposed: () => false,
    error: () => null, pause: () => media.pause(), tech: () => ({vhs: {dispose() { removed.push('vhs'); }}}),
    trigger: event => events.push(event), currentTime: () => media.currentTime };
  const root = { querySelector: () => null, contains: () => false };
  root.closest = () => root;
  const state = {matches: true, contentAuthorized: authorized, phase: authorized ? 'content' : 'advertisement',
    media, player, root, manager: {setDanmuProcess() {}, cleanAll() {}}};
  p.context.__bahaNativeBackend = 'android-exoplayer';
  p.load('native-hls'); p.load('native-playback');
  let current = true;
  const bridge = p.context.__bahaCreateNativePlayback({context: () => state, isCurrent: () => current});
  return {...p, bridge, state, media, player, events, removed, expire() { current = false; }};
}
test('native handoff cannot prepare or commit while ads or authorization remain', async () => {
  const p = nativePage(false);
  assert.equal(await p.bridge.prepare(), null);
  assert.equal(p.bridge.commit(), false);
  assert.equal(p.requests.length, 0);
});
test('native handoff unloads web media and feeds only actual native positions to official callbacks', async () => {
  const p = nativePage();
  assert.equal(p.bridge.hold(), true);
  const prepared = await p.bridge.prepare();
  assert.equal(prepared.position, 42);
  assert.equal(prepared.paused, false);
  const handoff = p.bridge.commit();
  assert.equal(handoff.position, 42);
  assert.equal(p.media.paused, true);
  assert.ok(p.removed.includes('src'));
  assert.ok(p.removed.includes('vhs'));
  assert.equal(p.bridge.sync({position: 45, duration: 120, paused: false, rate: 1, buffered: 55}), true);
  assert.equal(p.player.currentTime(), 45);
  assert.deepEqual(p.events, ['play', 'timeupdate']);
  assert.equal(p.bridge.sync({position: NaN, duration: 120, paused: false}), false);
  assert.equal(p.player.currentTime(), 45);
});
test('official revocation or an expired document stops native authorization and clock writes', async () => {
  const p = nativePage();
  p.bridge.hold(); await p.bridge.prepare(); p.bridge.commit();
  p.state.contentAuthorized = false;
  assert.equal(p.bridge.status(), 'denied');
  assert.equal(p.bridge.sync({position: 45, duration: 120, paused: false}), false);
  p.state.contentAuthorized = true;
  p.expire();
  assert.equal(p.bridge.status(), 'unavailable');
  assert.equal(p.bridge.sync({position: 45, duration: 120, paused: false}), false);
});
