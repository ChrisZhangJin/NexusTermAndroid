# Vendored search addon

`addon-search.js` is the unmodified UMD `lib/addon-search.js` from
`@xterm/addon-search@0.15.0`, compatible with xterm 5.x and Android WebView 87.
Do not replace it with current 0.16+ builds: those contain class static blocks
and logical assignment syntax that WebView 87 cannot parse.
MIT copyright and license are preserved in `LICENSE-addon-search`.

Reproduce (no npm install or transpilation required):

```sh
curl -fL https://registry.npmjs.org/@xterm/addon-search/-/addon-search-0.15.0.tgz -o /tmp/addon-search-0.15.0.tgz
mkdir -p /tmp/addon-search-0.15.0
tar xzf /tmp/addon-search-0.15.0.tgz -C /tmp/addon-search-0.15.0
cp /tmp/addon-search-0.15.0/package/lib/addon-search.js addon-search.js
cp /tmp/addon-search-0.15.0/package/LICENSE LICENSE-addon-search
```

SHA-256:
- tarball: `6fe6b2c0aa5cc51dc8888352ee276a790a64cfe25246a5af72a02afc373e172f`
- addon-search.js: `3cf52d71d9deb4ba60125087434c53e3fb35bb2249db9b13987991fd2db1c7bd`

The optional addon is loaded after the terminal opens; missing addon failures
leave SSH usable. `find` reports count -1 for unavailable search, and the native
find bar displays 查找不可用 instead of incorrectly claiming there are no matches.
