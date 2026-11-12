'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const source = readFileSync(join(__dirname, '../app/src/main/assets/adapters/qr-login.js'), 'utf8');
const sessionId = 'a'.repeat(64), token = 'b'.repeat(288);
const link = 'https://user.gamer.com.tw/link_login.php?token=' + token;

function page(options = {}) {
  const messages = [], requests = [], timers = new Map(), listeners = new Map();
  let now = 1_800_000_000_000, timerId = 0;
  class Clock extends Date { static now() { return now; } }
  const context = vm.createContext({
    URL, URLSearchParams, AbortController, Date:Clock,
    location:{origin:options.origin || 'https://ani.gamer.com.tw'},
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, {fn,delay}); return id; },
    clearTimeout(id) { timers.delete(id); },
    bahaPostJSON(raw) { messages.push(JSON.parse(raw)); },
    fetch:async (url, init) => {
      requests.push({url,init});
      const body = options.fetch ? await options.fetch(url, init, requests.length) :
        url.endsWith('link_login_request.php') ? {data:{sessionId,link,expiredAt:now/1000+180}} :
          {error:{status:'NOT_YET',code:1001}};
      return {ok:true,url,json:async()=>body, ...options.response};
    },
    Bahamut:{Csrf:class {
      setCookie() {}
      toString() { return '0123456789abcdef'; }
    }},
    __bahaDocumentNonce:'document-1',
    addEventListener(name, fn) { listeners.set(name,fn); },
    removeEventListener(name, fn) { if (listeners.get(name) === fn) listeners.delete(name); }
  });
  context.window = context;
  async function settle() { for (let i=0;i<15;i++) await Promise.resolve(); }
  return {context,messages,requests,timers,listeners,settle,
    async start() { vm.runInContext(source,context); await settle(); },
    advance(seconds) { now += seconds * 1000; },
    async poll() {
      const entry = [...timers].find(([,timer])=>timer.delay === 2000 || timer.delay === 3000);
      assert.ok(entry, 'Expected a scheduled poll');
      timers.delete(entry[0]); entry[1].fn(); await settle();
    }
  };
}

test('QR requests require the official origin and keep session credentials inside the page', async () => {
  const offsite = page({origin:'https://ani.gamer.com.tw.attacker.invalid'});
  await offsite.start();
  assert.equal(offsite.requests.length,0);
  const p = page(); await p.start();
  assert.equal(p.messages[0].type,'qr-login-ready');
  assert.equal(p.messages[0].link,link);
  assert.equal(p.messages[0].sessionId,undefined);
  await p.poll();
  const request = p.requests[1];
  assert.equal(request.url,'https://api.gamer.com.tw/user/v1/link_login.php');
  assert.equal(request.init.method,'POST');
  assert.equal(request.init.credentials,'include');
  assert.equal(request.init.cache,'no-store');
  assert.equal(request.init.redirect,'error');
  assert.equal(request.init.headers['X-Bahamut-Csrf-Token'],'0123456789abcdef');
  assert.equal(new URLSearchParams(request.init.body).get('sessionId'),sessionId);
  assert.equal(p.messages.length,1,'NOT_YET is still waiting');
});

test('QR creation rejects substituted links, duplicate query fields, invalid sessions and expiry', async () => {
  for (const override of [
    {link:link.replace('user.gamer.com.tw','user.gamer.com.tw.attacker.invalid')},
    {link:link.replace('https:','http:')},
    {link:link.replace('user.gamer','evil@user.gamer')},
    {link:link+'&token='+token}, {link:link+'#fragment'},
    {link:link.replace('/link_login.php','/login.php')},
    {link:'https://user.gamer.com.tw/link_login.php?token=short'},
    {sessionId:'invalid'}, {expiredAt:1}, {expiredAt:1_800_001_000}, {expiredAt:1_800_000_180.5}
  ]) {
    const p = page({fetch:async()=>({data:{sessionId,link,expiredAt:1_800_000_180,...override}})});
    await p.start();
    assert.deepEqual(p.messages.map(e=>e.type),['qr-login-error']);
    assert.equal(p.timers.size,0);
  }
});

test('QR authorization success requests a fresh official account check, and stops polling', async () => {
  const p = page({fetch:async (_,__,count)=>count === 1 ?
    {data:{sessionId,link,expiredAt:1_800_000_180}} : {data:{status:'OK'}}});
  await p.start(); await p.poll();
  assert.deepEqual(p.messages.map(e=>e.type),['qr-login-ready','qr-login-complete']);
  assert.equal(p.timers.size,0);
  assert.equal(p.listeners.has('pagehide'),false);
});

test('expiration hides the QR code and never sends an expired session to the API', async () => {
  const p = page(); await p.start();
  p.advance(180); await p.poll();
  assert.equal(p.requests.length,1);
  assert.equal(p.messages.at(-1).type,'qr-login-expired');
  assert.equal(p.timers.size,0);
});

test('refresh cancels a pending request and ignores its late response', async () => {
  let release;
  const p = page({fetch:async (_,__,count)=>count === 1 ?
    new Promise(resolve=>{release=resolve;}) : {data:{sessionId,link,expiredAt:1_800_000_180}}});
  await p.start();
  const oldSignal = p.requests[0].init.signal;
  await p.start();
  assert.equal(oldSignal.aborted,true);
  release({data:{sessionId,link:link.replace(token,'c'.repeat(288)),expiredAt:1_800_000_180}});
  await p.settle();
  assert.equal(p.messages.length,1);
  assert.equal(p.messages[0].link,link);
});

test('pause aborts in-flight polling and resume checks expiry before restarting', async () => {
  let release;
  const p = page({fetch:async (_,__,count)=>count === 1 ?
    {data:{sessionId,link,expiredAt:1_800_000_180}} : new Promise(resolve=>{release=resolve;})});
  await p.start(); await p.poll();
  p.context.__bahaQrLogin.pause();
  assert.equal(p.requests[1].init.signal.aborted,true);
  assert.equal(p.timers.size,1,'Only the aborted fetch cleanup timeout remains');
  release({data:{status:'OK'}}); await p.settle();
  assert.equal(p.messages.length,1,'Paused responses cannot complete login');
  assert.equal(p.timers.size,0);
  p.advance(181);
  p.context.__bahaQrLogin.resume(); await p.settle();
  assert.equal(p.messages.at(-1).type,'qr-login-expired');
  assert.equal(p.requests.length,2);
});

test('network retries are bounded and official refusal cannot report success', async () => {
  const p = page({fetch:async()=>{throw Error('offline');}});
  await p.start(); await p.poll(); await p.poll();
  assert.deepEqual(p.messages.map(e=>e.type),['qr-login-retrying','qr-login-retrying','qr-login-error']);
  assert.equal(p.timers.size,0);
  for (const result of [{error:{status:'DENIED'}},{error:{status:'EXPIRED'}},{}]) {
    const p = page({fetch:async (_,__,count)=>count === 1 ?
      {data:{sessionId,link,expiredAt:1_800_000_180}} : result});
    await p.start(); await p.poll();
    assert.equal(p.messages.at(-1).type,'qr-login-error');
    assert.equal(p.timers.size,0);
  }
});

test('off-origin redirects and stale documents cannot publish authorization state', async () => {
  const redirect = page({response:{url:'https://attacker.invalid/'}});
  await redirect.start(); await redirect.poll(); await redirect.poll();
  assert.equal(redirect.messages.at(-1).type,'qr-login-error');
  const p = page(); await p.start();
  p.context.__bahaDocumentNonce = 'document-2';
  await p.poll();
  assert.equal(p.requests.length,1);
  assert.equal(p.messages.length,1);
});
