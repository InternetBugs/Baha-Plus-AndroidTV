'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { transformSync, buildSync } = require('esbuild');
const { parse } = require('acorn');

const root = path.resolve(__dirname, '..');
const source = path.join(root, 'app/src/main/js/adapters');
const output = path.resolve(process.argv[2] || path.join(root, 'app/build/generated/webviewAssets'));
const adapters = fs.readdirSync(source).filter(name => name.endsWith('.js')).sort();
const destination = path.join(output, 'adapters');
fs.mkdirSync(destination, { recursive: true });
for (const name of adapters) {
  const result = transformSync(fs.readFileSync(path.join(source, name), 'utf8'), {
    target: 'chrome74',
    sourcefile: name,
    charset: 'utf8',
    legalComments: 'inline'
  });
  // Reject newer syntax in app-owned adapters before it reaches an APK.
  // The separate runtime compiler handles official static scripts on demand.
  parse(result.code, { ecmaVersion: 2019 });
  fs.writeFileSync(path.join(destination, name), result.code, 'utf8');
}
// Only remove stale generated adapter files, never the source directory.
for (const name of fs.readdirSync(destination)) {
  if (name.endsWith('.js') && !adapters.includes(name)) {
    fs.unlinkSync(path.join(destination, name));
  }
}
const compat = path.join(output, 'compat');
fs.mkdirSync(compat, { recursive: true });
fs.copyFileSync(require.resolve('@babel/standalone/babel.min.js'), path.join(compat, 'babel.min.js'));
fs.copyFileSync(require.resolve('@babel/standalone/LICENSE'), path.join(compat, 'Babel-LICENSE.txt'));
fs.copyFileSync(path.join(root, 'app/src/main/js/compat/compiler.js'), path.join(compat, 'compiler.js'));
const polyfill = buildSync({
  stdin: { contents: "import 'core-js/actual/string/replace-all';", resolveDir: root },
  bundle: true, write: false, target: 'chrome74', format: 'iife',
  platform: 'browser', minify: true, legalComments: 'inline'
}).outputFiles[0].text;
fs.writeFileSync(path.join(compat, 'polyfills.js'),
  `;(function(){if(typeof String.prototype.replaceAll!=='function'){\n${polyfill}\n}})();\n`, 'utf8');
fs.copyFileSync(path.join(root, 'node_modules/core-js/LICENSE'), path.join(compat, 'CoreJS-LICENSE.txt'));
for (const name of ['babel.min.js', 'compiler.js', 'polyfills.js']) {
  parse(fs.readFileSync(path.join(compat, name), 'utf8'), { ecmaVersion: 2019 });
}
fs.writeFileSync(path.join(compat, 'index.html'), `<!doctype html>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-eval'; connect-src 'none'; frame-src 'none'">
<script src="/babel.min.js"></script><script src="/compiler.js"></script>`, 'utf8');
console.log(`Compiled ${adapters.length} adapters for WebView 74; ECMAScript 2019 syntax verified.`);
