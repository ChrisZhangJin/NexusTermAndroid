Vendored browser libraries for the 「浏览」 viewer (copied/built from /root/workspace/NexusTerm/node_modules).
No network access is needed at load time; viewer.html references them by relative URL.

marked.min.js        marked 18.1.1 (MIT)           UMD build (lib/marked.umd.js) minified with esbuild; global `marked`.   LICENSE-marked
purify.min.js        DOMPurify 3.4.17 (MPL-2.0 OR Apache-2.0)  dist/purify.min.js unmodified; global `DOMPurify`.          LICENSE-dompurify
highlight.min.js     highlight.js 11.12.0 (BSD-3-Clause)  core + json, yaml, ini, properties, xml, bash, nginx,
                     dockerfile, makefile, sql, plaintext; single IIFE bundle built with esbuild 0.28.2; global `hljs`.    LICENSE-highlightjs
polyfill.js        Array.prototype.at / Object.hasOwn shims for WebView < Chrome 93 (marked needs them); load before marked.min.js.
hljs-github.css      highlight.js styles/github.css (BSD-3-Clause)

Rebuild highlight.min.js (from the NexusTerm repo root):
  cat > /tmp/hl-entry.js <<'X'
  import hljs from "highlight.js/lib/core";
  import json from "highlight.js/lib/languages/json"; ... (register the languages listed above)
  window.hljs = hljs;
  X
  node_modules/.bin/esbuild /tmp/hl-entry.js --bundle --minify --format=iife --legal-comments=none --outfile=highlight.min.js
Rebuild marked.min.js:
  node_modules/.bin/esbuild node_modules/marked/lib/marked.umd.js --minify --legal-comments=none --outfile=marked.min.js
