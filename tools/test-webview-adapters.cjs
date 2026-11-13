'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
function run(args, env = process.env) {
  const result = spawnSync(process.execPath, args, { cwd: root, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
run(['tools/build-webview-adapters.cjs']);
const tests = fs.readdirSync(path.join(root, 'tests'))
  .filter(name => name.endsWith('.test.cjs')).sort()
  .map(name => path.join('tests', name));
for (const [label, directory] of [
  ['source', 'app/src/main/js/adapters'],
  ['WebView 74 compiled assets', 'app/build/generated/webviewAssets/adapters']
]) {
  console.log(`Testing ${label}`);
  run(['--test', ...tests], {
    ...process.env,
    BAHA_ADAPTERS_DIR: path.join(root, directory)
  });
}
