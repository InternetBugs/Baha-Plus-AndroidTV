(function () {
  'use strict';
  // This file runs only in an isolated, local WebView. Downloaded code is
  // parsed and translated as text; it is never executed in the compiler.
  function options(major) {
    return {
      presets: [['env', {
        targets: { chrome: String(major) },
        modules: false,
        bugfixes: true,
        useBuiltIns: false
      }]],
      sourceType: 'unambiguous',
      ast: true,
      comments: true,
      compact: true,
      sourceMaps: false,
      babelrc: false,
      configFile: false
    };
  }
  function supportsPlayerSyntax() {
    try {
      new Function('class A { #f() { return 1; } get #v() { return this.#f(); } read() { return this.#v; } } var a; a ??= 1; a &&= 2; a ||= 3;');
      return true;
    } catch (_) { return false; }
  }
  function translate(source, major, polyfills) {
    var config = options(major);
    if (polyfills) {
      config.plugins = [function () {
        return { visitor: { Program: function (path) {
          // Babel stores directives separately from the statement body.
          // Insert after them so strict scripts stay strict and sloppy ones
          // stay sloppy. The patch is APK-owned text, never executed here.
          var patch = Babel.packages.parser.parse(polyfills, { sourceType: 'script' });
          path.node.body = patch.program.body.concat(path.node.body);
        } } };
      }];
    }
    var result = Babel.transform(source, config);
    return { code: result.code, module: result.ast.program.sourceType === 'module' };
  }
  function transform(source, major, polyfills) {
    return translate(source, major, polyfills).code;
  }
  var api = { options: options, supportsPlayerSyntax: supportsPlayerSyntax, transform: transform };
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
    return;
  }
  window.bahaCompile = function (id, major) {
    try {
      var parts = [], part, index = 0;
      while ((part = compiler.input(id, index++)) !== '') parts.push(part);
      var translated = translate(parts.join(''), major, compiler.polyfills());
      var output = translated.code;
      // Check classic scripts in the actual device's parser. Module imports
      // remain module imports, resolved normally at their original URL.
      if (!translated.module) new Function(output);
      for (index = 0; index < output.length; index += 32768) {
        compiler.output(id, output.slice(index, index + 32768));
      }
      compiler.finish(id, true);
    } catch (_) { compiler.finish(id, false); }
  };
  compiler.ready(Babel.version === '7.28.5');
})();
