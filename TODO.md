# TODO — Round 2

Status: `[ ]` pending · `[x]` done

## 1. Menu trigger
- [x] Two-finger hold 2s (no movement, no pinch) opens the menu, with haptic
- [x] Remove two-finger tap trigger and three-finger hold selection path

## 2. Scrolling
- [x] Single-finger vertical scroll 1.6× + fling inertia (JS, only when mouse tracking is off)
- [x] Menu item "滑至底部"

## 3. Key bar
- [x] Auto-hide after 2s of idleness (no touch / input; not while CTRL/ALT armed), terminal resizes
- [x] Summon by overscroll: at buffer bottom, single finger keeps pushing up ~60px
- [x] Extra pages: symbols page, F1–F12 + INS + DEL page
- [x] Two-finger horizontal swipe on the bar switches pages, page dots
- [x] Terminal gestures ignore pointers that start on the bar

## 4. Selection
- [x] Single-finger long press 2s → select word under finger, haptic, handle pop-in, ripple
- [x] Three-finger slide moves selection end (relative, trackpad-like)
- [x] Single tap ends selection → copy to clipboard (tap consumed)

## 5. Icon & colours
- [x] Adaptive launcher icon from NexusTerm/icon-design/icon.svg
- [x] Green MaterialTheme, themes.xml, key bar / JS accent colours

## 6. Help
- [x] "?" mini FAB above "+" on the session list → 操作说明 dialog

## 7. Notification
- [x] Foreground service with ongoing "N 个会话已连接" notification listing tabs
- [x] POST_NOTIFICATIONS request on API 33+, stop when no sessions

## 8. Wrap-up
- [x] Build, install, launch, check logcat
- [x] Update README gesture docs

---

# TODO — Round 3: 「浏览」(file viewer, ported from desktop NexusTerm)

Reference: `/root/workspace/NexusTerm` — `src-tauri/src/viewer.rs`, `src/lib/viewable.ts`,
`src/viewer/*.tsx`, `src/components/TerminalView.tsx` (`viewablePath`/`wordAtPoint`/`doView`).
Status: `[ ]` pending · `[~]` in progress · `[x]` done

Design decisions
- Viewer = new `ViewerActivity` hosting a WebView on a bundled page `assets/viewer/viewer.html`
  (reuses marked + DOMPurify + highlight.js from desktop's node_modules; no network needed to load).
- Document bytes never go through `addJavascriptInterface` (it is exposed to *every* frame, including
  the sandboxed HTML iframe). The page fetches them from `https://nxviewer.local/doc`, answered by
  `WebViewClient.shouldInterceptRequest` streaming a cache-dir temp file (deleted in `onDestroy`).
- HTML files: sandboxed iframe, **no `allow-same-origin`**, scripts off by default, "允许脚本" toggle,
  CSP like desktop's `csp()`. Markdown: marked → DOMPurify, external links open in system browser.
- Types/limits identical to desktop `kind_of`: md/html 20MB, image 50MB, audio 50MB, video 20MB,
  code (json/yaml/toml/ini/conf/xml/sql/txt/log/.env/Dockerfile/…, no-extension = plain text) 20MB.
- Read path: SFTP panel → existing `sftpRead`; terminal → new `ssh_read_file` (exec `cat`, needs
  `sidecar=true` connection; same limits as desktop `SessionManager::read_file`).
- Read-only. Never type into remote terminals; never touch the user's live tmux `accid`.

Wave 1 (parallel, no overlap)
- [x] 1a. Web page: vendor marked/DOMPurify/highlight.js (subset) into `assets/viewer/`; write
      `viewer.html` + `viewer.js` (+css) implementing all six kinds, source toggle, zoom, JSON
      check/format, wrap, copy, image pan/zoom, audio/video, HTML sandbox + JS toggle; UTF-8→GBK decode.
      Testable on host with node (jsdom or similar) — no device needed.
- [x] 1b. nexus-core: export `ssh_read_file(session_id, path, max)` (port of desktop `read_file` +
      `cat_command`, 30s timeout, SIDECAR_OFF/DENIED semantics as `probe`); unit test; Kotlin wrapper
      `SshClient.readFile`.

Wave 2
- [x] 2. `ViewerActivity` + manifest + intent extras (source label, kind, temp file) + request
      interception + external-link handling; kind detection in Kotlin (port of `viewable.ts`);
      "浏览" action in SftpScreen remote pane (reads via `SftpClient.read`, size-limit/err messages).
      Build with `gradle :app:assembleDebug --offline -q`.

Wave 3
- [x] 3. Terminal entry: extra-menu / long-press menu item "浏览" in the terminal (selection or
      word under finger → `viewablePath` rules incl. `requireExt` for auto-picked words; relative path
      resolved via `probe` cwd; clear toasts for 兼容模式/no cwd); Help dialog + README section.

Wave 4
- [x] 4. Device test (MI 8 SE, Android 10 / WebView 87, 3 rounds; not covered: selection-based browse, two-finger gestures, mp4, real hardware keyboard) on the plugged-in phone against the test server (md/html/png/json/log/mp3/mp4,
      oversize file, binary-ish file, missing file, GBK file, HTML script toggle, rotate, back stack).
- [ ] 5. Fix findings (done: marked polyfill for old WebView, iframe links via <base target=_blank>, error wording, directory message, switcher IME focus release), bump version, commit (only when asked).
