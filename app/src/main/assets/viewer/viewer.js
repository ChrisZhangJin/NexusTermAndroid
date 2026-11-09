/*
 * NexusTerm Android「浏览」页面 —— 桌面版 src/viewer/*.tsx 的原生 JS 移植。
 *
 * 与原生侧（ViewerActivity）的约定（页面只做三件事，不依赖 addJavascriptInterface）：
 *   1. GET /doc-meta  → JSON {name, source, kind, size}
 *        kind ∈ markdown | html | image | audio | video | code
 *        （可选 {error: "..."} 表示原生侧已知失败，页面直接显示「无法打开：…」）
 *   2. GET /doc       → 原始字节（fetch → arrayBuffer）
 *   3. HTML 文档的 iframe：src = /doc-html 或 /doc-html?js=1（由原生加 CSP 头）。
 *      iframe 带 sandbox（无脚本："allow-popups"；有脚本："allow-scripts allow-popups"），
 *      永远不加 allow-same-origin；切换「允许脚本」会换 src + 重建 iframe。
 *   以上路径都是相对页面 origin 的绝对路径（以 / 开头）；页面自身资源只用相对 URL（vendor/…）。
 *
 * 页面向原生汇报的唯一方式是「导航」：
 *   - Markdown 里的外链（http/https/mailto）：preventDefault 后 window.location.assign(href)。
 *     原生在 shouldOverrideUrlLoading 里拦截一切离开 viewer origin 的顶层导航并交给系统浏览器。
 *     不使用 window.open / target=_blank / location.href 赋值之外的任何手段。
 *   - 锚点链接（#x）：在页内滚动，不产生导航。
 *   - 其它协议（javascript: / data: / file: …）一律忽略。
 *
 * 全局只暴露 window.NXViewer（供宿主侧测试读取就绪状态与纯函数），不暴露任何能力给 iframe。
 */
(function () {
  "use strict";

  /* ───────────────────────── viewable.ts 移植 ───────────────────────── */

  var IMAGE_MIME = {
    png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", jfif: "image/jpeg", gif: "image/gif",
    webp: "image/webp", bmp: "image/bmp", ico: "image/x-icon", svg: "image/svg+xml", avif: "image/avif",
  };
  var AUDIO_MIME = {
    mp3: "audio/mpeg", wav: "audio/wav", flac: "audio/flac", ogg: "audio/ogg", m4a: "audio/mp4",
    aac: "audio/aac", opus: "audio/opus", wma: "audio/x-ms-wma",
  };
  var VIDEO_MIME = {
    mp4: "video/mp4", webm: "video/webm", mov: "video/quicktime", mkv: "video/x-matroska",
    avi: "video/x-msvideo", m4v: "video/mp4",
  };
  var MD_EXT = ["md", "markdown", "mdown", "mkd", "mkdn"];
  var HTML_EXT = ["html", "htm", "xhtml"];
  var CODE_EXT = {
    json: "json", jsonc: "json", json5: "json", yaml: "yaml", yml: "yaml", toml: "ini", ini: "ini",
    cfg: "ini", conf: "ini", cnf: "ini", env: "ini", service: "ini", properties: "properties",
    xml: "xml", plist: "xml", sql: "sql", txt: "plaintext", log: "plaintext",
  };
  var CODE_NAMES = {
    ".env": "ini", ".gitconfig": "ini", ".gitmodules": "ini", ".editorconfig": "ini", ".npmrc": "ini",
    ".bashrc": "bash", ".zshrc": "bash", ".profile": "bash", ".bash_profile": "bash",
    dockerfile: "dockerfile", makefile: "makefile",
  };
  var has = function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); };

  function baseName(path) {
    var parts = String(path).split(/[/\\]/).filter(Boolean);
    return (parts[parts.length - 1] || String(path)).toLowerCase();
  }
  function extOf(name) {
    var i = name.lastIndexOf(".");
    if (i <= 0 || i === name.length - 1) return null;
    return name.slice(i + 1);
  }
  function viewableKind(path, opts) {
    var name = baseName(path);
    if (has(CODE_NAMES, name) || name.indexOf(".env.") === 0) return "code";
    var ext = extOf(name);
    if (!ext) {
      if (opts && opts.requireExt) return null;
      return name.indexOf(".") >= 0 ? null : "code";
    }
    if (MD_EXT.indexOf(ext) >= 0) return "markdown";
    if (HTML_EXT.indexOf(ext) >= 0) return "html";
    if (has(IMAGE_MIME, ext)) return "image";
    if (has(AUDIO_MIME, ext)) return "audio";
    if (has(VIDEO_MIME, ext)) return "video";
    if (has(CODE_EXT, ext)) return "code";
    return null;
  }
  function mimeFrom(table, path) {
    var e = extOf(baseName(path)) || "";
    return has(table, e) ? table[e] : "application/octet-stream";
  }
  var imageMime = function (p) { return mimeFrom(IMAGE_MIME, p); };
  var audioMime = function (p) { return mimeFrom(AUDIO_MIME, p); };
  var videoMime = function (p) { return mimeFrom(VIDEO_MIME, p); };
  var isSvg = function (p) { return extOf(baseName(p)) === "svg"; };
  var isJson = function (p) { return extOf(baseName(p)) === "json"; };
  function codeLang(path) {
    var name = baseName(path);
    if (has(CODE_NAMES, name)) return CODE_NAMES[name];
    if (name.indexOf(".env.") === 0) return "ini";
    var ext = extOf(name) || "";
    if (ext === "conf" && name.indexOf("nginx") >= 0) return "nginx";
    return has(CODE_EXT, ext) ? CODE_EXT[ext] : "plaintext";
  }
  function kindLabel(path, kind) {
    if (kind === "markdown") return "MD";
    if (kind === "html") return "HTML";
    var name = baseName(path);
    if (name.indexOf(".env") === 0) return "ENV";
    if (has(CODE_NAMES, name)) return name.replace(/^\./, "").toUpperCase().slice(0, 10);
    return (extOf(name) || "TXT").toUpperCase();
  }

  /** mediaError.ts：把 HTMLMediaElement.error 翻成人话 */
  function mediaErrorDetail(err) {
    if (!err) return "未知错误";
    var codeText = { 1: "加载被中止", 2: "网络错误", 3: "解码失败（文件可能已损坏）", 4: "格式或编码不受支持" };
    var base = codeText[err.code] || "错误码 " + err.code;
    var raw = err.message && String(err.message).trim();
    return raw ? base + "（" + raw + "）" : base;
  }

  /** UTF-8 优先；非法时回退 GBK（国内老文档常见），再不行宽松 UTF-8 */
  function decode(bytes) {
    try {
      return { text: new TextDecoder("utf-8", { fatal: true }).decode(bytes), enc: "UTF-8" };
    } catch (e) {
      try {
        return { text: new TextDecoder("gbk").decode(bytes), enc: "GBK" };
      } catch (e2) {
        return { text: new TextDecoder("utf-8").decode(bytes), enc: "UTF-8(容错)" };
      }
    }
  }

  function fmtSize(n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / 1024 / 1024).toFixed(1) + " MB";
  }

  /* ───────────────────────── 小工具 ───────────────────────── */

  function h(tag, props, children) {
    var el = document.createElement(tag);
    if (props) {
      for (var k in props) {
        var v = props[k];
        if (v === undefined || v === null || v === false) continue;
        if (k === "class") el.className = v;
        else if (k === "text") el.textContent = v;
        else if (k.slice(0, 2) === "on") el.addEventListener(k.slice(2), v);
        else if (k === "style") el.style.cssText = v;
        else el.setAttribute(k, v === true ? "" : v);
      }
    }
    (children || []).forEach(function (c) {
      if (c === null || c === undefined || c === false) return;
      el.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return el;
  }
  function lsGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* ignore */ } }
  function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }

  var toastTimer = 0;
  function notify(msg) {
    var body = document.getElementById("rd-body");
    if (!body) return;
    var old = body.querySelector(".rd-toast");
    if (old) old.remove();
    body.appendChild(h("div", { class: "rd-toast", text: msg }));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      var t = body.querySelector(".rd-toast");
      if (t) t.remove();
    }, 1800);
  }

  /** 剪贴板：优先 navigator.clipboard；失败（非安全上下文 / 无焦点）退回 textarea + execCommand */
  function copyText(text) {
    function fallback() {
      var ta = h("textarea", { readonly: true, style: "position:fixed;left:-9999px;top:0;opacity:0" });
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      ta.setSelectionRange(0, text.length);
      var ok = false;
      try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
      ta.remove();
      return ok ? Promise.resolve() : Promise.reject(new Error("浏览器拒绝了复制"));
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text).catch(fallback);
    }
    return fallback();
  }

  /* ───────────────────────── 状态 ───────────────────────── */

  var state = {
    doc: null,        // {name, source, kind, bytes, text, enc}
    mode: "render",   // render | source
    allowJs: false,
    zoom: Number(lsGet("nx.viewer.zoom")) || 100,
    imgInfo: "",
  };
  var blobUrls = {};   // 同一文档只建一次 Blob URL（切换渲染/源码不重复建）
  var zoomHooks = [];  // 当前视图里随字号缩放变化的更新函数

  function blobUrl(mime) {
    var key = mime;
    if (!blobUrls[key]) blobUrls[key] = URL.createObjectURL(new Blob([state.doc.bytes], { type: mime }));
    return blobUrls[key];
  }
  window.addEventListener("pagehide", function () {
    for (var k in blobUrls) { try { URL.revokeObjectURL(blobUrls[k]); } catch (e) { /* ignore */ } }
    blobUrls = {};
  });

  function setZoom(z) {
    state.zoom = clamp(Math.round(z), 50, 250);
    lsSet("nx.viewer.zoom", String(state.zoom));
    zoomHooks.forEach(function (f) { f(); });
  }

  /* ───────────────────────── Markdown ───────────────────────── */

  function slugify(text) {
    return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, "").replace(/\s/g, "-");
  }

  function renderMarkdown(text) {
    var raw = marked.parse(text, { gfm: true, breaks: false, async: false });
    var clean = DOMPurify.sanitize(raw, {
      USE_PROFILES: { html: true },
      // input 保留：GFM 任务列表的复选框（marked 生成的是 disabled）
      FORBID_TAGS: ["style", "form", "button", "iframe", "object", "embed"],
    });
    var art = h("article", { class: "rd-md" });
    art.innerHTML = clean; // 已 DOMPurify
    // 标题锚点（marked 新版不再自动生成 id；按 GitHub 规则补上，重名加 -1、-2…）
    var seen = {};
    art.querySelectorAll("h1,h2,h3,h4,h5,h6").forEach(function (hd) {
      var s = slugify(hd.textContent || "");
      if (!s) return;
      var n = seen[s] || 0;
      seen[s] = n + 1;
      hd.id = n ? s + "-" + n : s;
    });
    // 图片：只放行 http(s) / data；相对路径在这里没有意义（也不应去打原生侧的拦截器）
    art.querySelectorAll("img").forEach(function (im) {
      var src = im.getAttribute("src") || "";
      if (/^(https?:|data:image\/)/i.test(src)) {
        im.setAttribute("referrerpolicy", "no-referrer");
        im.setAttribute("loading", "lazy");
      } else {
        im.replaceWith(h("span", { text: "[图片：" + (im.getAttribute("alt") || src) + "]" }));
      }
    });
    art.addEventListener("click", function (e) {
      var t = e.target;
      var a = t && t.closest ? t.closest("a") : null;
      if (!a) return;
      var href = a.getAttribute("href") || "";
      e.preventDefault();
      if (href.charAt(0) === "#") {
        var id = href.slice(1);
        try { id = decodeURIComponent(id); } catch (err) { /* keep raw */ }
        var target = null;
        art.querySelectorAll("[id]").forEach(function (n) { if (!target && n.id === id) target = n; });
        if (target) target.scrollIntoView({ block: "start" });
      } else if (/^(https?:|mailto:)/i.test(href)) {
        window.location.assign(href);
      }
      // 其它协议一律忽略
    });
    return art;
  }

  /* ───────────────────────── 代码 ───────────────────────── */

  var HL_LIMIT = 1024 * 1024; // 超过不做高亮（同步，卡）

  function checkJson(text) {
    try {
      return { ok: true, pretty: JSON.stringify(JSON.parse(text), null, 2) };
    } catch (e) {
      var msg = e instanceof Error ? e.message : String(e);
      var line;
      var lc = /line (\d+)/i.exec(msg);
      var pos = /position (\d+)/i.exec(msg);
      if (lc) line = Number(lc[1]);
      else if (pos) line = text.slice(0, Number(pos[1])).split("\n").length;
      return { ok: false, msg: msg, line: line };
    }
  }

  function buildCode(text, lang, json) {
    var wrap = lsGet("nx.viewer.wrap") === "1";
    var pretty = false;
    var js = json ? checkJson(text) : null;

    var root = h("div", { class: "rd-code-root" });
    var tools = h("div", { class: "rd-tools" });
    var scroll = h("div", { class: "rd-code-scroll" });
    root.appendChild(tools);
    root.appendChild(scroll);

    function fs() { return (13 * state.zoom) / 100 + "px"; }

    function paint() {
      var shown = pretty && js && js.ok ? js.pretty : text;
      var html = null;
      if (shown.length <= HL_LIMIT && hljs.getLanguage(lang)) {
        try { html = hljs.highlight(shown, { language: lang, ignoreIllegals: true }).value; } catch (e) { html = null; }
      }
      var n = shown.split("\n").length;
      var lines = shown.charAt(shown.length - 1) === "\n" ? n - 1 : n;
      var errLine = js && !js.ok && !pretty ? js.line : undefined;

      // 工具条
      tools.textContent = "";
      if (js) {
        if (js.ok) tools.appendChild(h("span", { class: "rd-ok", text: "✓ JSON 语法正确" }));
        else tools.appendChild(h("span", { class: "rd-bad", text: "✗ JSON 语法错误" + (js.line ? "（第 " + js.line + " 行）" : "") + "：" + js.msg }));
      }
      if (js && js.ok) {
        tools.appendChild(h("label", { class: "rd-chip" + (pretty ? " on" : "") }, [
          h("input", { type: "checkbox", checked: pretty, onchange: function (e) { pretty = e.target.checked; paint(); } }),
          "格式化",
        ]));
        // setAttribute("checked","") 才是 true；false 在 h() 里被跳过，所以不需要额外处理
      }
      tools.appendChild(h("label", { class: "rd-chip" + (wrap ? " on" : "") }, [
        h("input", { type: "checkbox", checked: wrap, onchange: function (e) { wrap = e.target.checked; lsSet("nx.viewer.wrap", wrap ? "1" : "0"); paint(); } }),
        "自动换行",
      ]));
      tools.appendChild(h("span", { class: "rd-spacer" }));
      tools.appendChild(h("span", {
        class: "rd-hint rd-hide-narrow",
        text: (lang === "plaintext" ? "纯文本" : lang) + " · " + lines + " 行" +
          (html === null && shown.length > HL_LIMIT ? " · 文件较大，已关闭高亮" : ""),
      }));
      tools.appendChild(h("button", {
        class: "rd-btn", text: "复制全部",
        onclick: function () {
          copyText(shown).then(
            function () { notify("已复制全部内容"); },
            function (e) { notify("复制失败：" + (e && e.message ? e.message : e)); }
          );
        },
      }));

      // 正文（保持横/纵滚动位置不是必须；重绘时回到顶部即可）
      var code = h("div", { class: "rd-code" + (wrap ? " wrap" : ""), style: "font-size:" + fs() });
      if (!wrap) {
        var nums = [];
        for (var i = 1; i <= lines; i++) nums.push(i === errLine ? '<span class="err">' + i + "</span>" : String(i));
        var gutter = h("pre", { class: "rd-gutter", "aria-hidden": "true" });
        gutter.innerHTML = nums.join("\n"); // 只有数字，无需转义
        code.appendChild(gutter);
      }
      var pre = h("pre", { class: "hljs" });
      if (html !== null) pre.innerHTML = html; // hljs 输出已转义
      else pre.textContent = shown;
      code.appendChild(pre);
      scroll.textContent = "";
      scroll.appendChild(code);
    }
    paint();
    zoomHooks.push(function () {
      var c = scroll.querySelector(".rd-code");
      if (c) c.style.fontSize = fs();
    });
    return root;
  }

  /* ───────────────────────── 图片 ───────────────────────── */

  var IMG_MIN = 0.02, IMG_MAX = 32;

  function buildImage(url) {
    var bg = lsGet("nx.viewer.imgbg") || "checker";
    var nat = null;
    var v = { s: 1, x: 0, y: 0 };
    var fit = true;

    var root = h("div", { class: "rd-img-root" });
    var box = h("div", { class: "rd-img-box rd-bg-" + bg });
    var img = h("img", { alt: "", draggable: "false", style: "visibility:hidden" });
    var pctEl = h("span", { class: "rd-zoom-ro", text: "100%" });
    var fitBtn = h("button", { text: "适应", onclick: function () { fit = true; layout(); } });
    var oneBtn = h("button", { text: "1:1", onclick: function () { fit = false; place(1); } });
    var bgBtns = {};
    function setBg(b) {
      bg = b;
      lsSet("nx.viewer.imgbg", b);
      box.className = "rd-img-box rd-bg-" + b;
      for (var k in bgBtns) bgBtns[k].className = k === b ? "on" : "";
    }
    ["checker", "dark", "light"].forEach(function (b) {
      bgBtns[b] = h("button", { text: b === "checker" ? "▦" : b === "dark" ? "■" : "□", onclick: function () { setBg(b); } });
    });
    setBg(bg);

    var tools = h("div", { class: "rd-tools" }, [
      h("div", { class: "rd-seg" }, [fitBtn, oneBtn]),
      h("div", { class: "rd-zoom" }, [
        h("button", { text: "−", onclick: function () { zoomAt(v.s / 1.25); } }),
        pctEl,
        h("button", { text: "+", onclick: function () { zoomAt(v.s * 1.25); } }),
      ]),
      h("div", { class: "rd-seg" }, [bgBtns.checker, bgBtns.dark, bgBtns.light]),
      h("span", { class: "rd-hint rd-hide-narrow", text: "双指缩放 · 拖动平移 · 双击切换适应/1:1" }),
    ]);

    function apply() {
      if (!nat) return;
      img.style.width = nat.w * v.s + "px";
      img.style.height = nat.h * v.s + "px";
      img.style.transform = "translate(" + v.x + "px," + v.y + "px)";
      img.style.visibility = "visible";
      img.className = v.s >= 3 ? "pixelated" : "";
      var pct = Math.round(v.s * 100);
      pctEl.textContent = pct + "%";
      fitBtn.className = fit ? "on" : "";
      oneBtn.className = !fit && pct === 100 ? "on" : "";
    }
    function place(s) {
      if (!nat) return;
      v = { s: s, x: (box.clientWidth - nat.w * s) / 2, y: (box.clientHeight - nat.h * s) / 2 };
      apply();
    }
    function fitScale() {
      if (!nat || !nat.w || !nat.h) return 1;
      return Math.min(1, (box.clientWidth - 24) / nat.w, (box.clientHeight - 24) / nat.h);
    }
    function layout() { if (fit) place(fitScale()); else apply(); }
    function zoomAt(s, cx, cy) {
      var ns = clamp(s, IMG_MIN, IMG_MAX);
      var px = cx === undefined ? box.clientWidth / 2 : cx;
      var py = cy === undefined ? box.clientHeight / 2 : cy;
      fit = false;
      v = { s: ns, x: px - ((px - v.x) * ns) / v.s, y: py - ((py - v.y) * ns) / v.s };
      apply();
    }

    img.addEventListener("load", function () {
      nat = { w: img.naturalWidth || 512, h: img.naturalHeight || 512 }; // 只有 viewBox 的 SVG 给默认画布
      state.imgInfo = nat.w + " × " + nat.h + " px";
      var m = document.getElementById("rd-meta-img");
      if (m) m.textContent = " · " + state.imgInfo;
      layout();
    });
    img.addEventListener("error", function () {
      box.textContent = "";
      box.appendChild(h("div", { class: "rd-error", text: "图片无法解码（文件损坏，或格式不受支持）" }));
    });
    img.src = url;
    box.appendChild(img);
    if (typeof ResizeObserver !== "undefined") new ResizeObserver(function () { if (fit) layout(); }).observe(box);

    // 鼠标滚轮缩放
    box.addEventListener("wheel", function (e) {
      e.preventDefault();
      var r = box.getBoundingClientRect();
      zoomAt(v.s * (e.deltaY < 0 ? 1.15 : 1 / 1.15), e.clientX - r.left, e.clientY - r.top);
    }, { passive: false });

    // 触摸 / 鼠标指针：单指拖动平移，双指捏合缩放（带平移），双击切换适应 / 1:1
    var ptrs = {};          // id → {x,y}
    var gesture = null;     // {kind:"pan"|"pinch", ...起始快照}
    var tap = { t: 0, x: 0, y: 0, ok: false, last: 0, lx: 0, ly: 0 };
    function count() { return Object.keys(ptrs).length; }
    function rel(e) { var r = box.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
    function startGesture() {
      var ids = Object.keys(ptrs);
      if (ids.length >= 2) {
        var a = ptrs[ids[0]], b = ptrs[ids[1]];
        gesture = {
          kind: "pinch", id0: ids[0], id1: ids[1], v0: { s: v.s, x: v.x, y: v.y },
          d0: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), c0: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
        };
      } else if (ids.length === 1) {
        var p = ptrs[ids[0]];
        gesture = { kind: "pan", id: ids[0], sx: p.x, sy: p.y, vx: v.x, vy: v.y };
      } else gesture = null;
    }
    box.addEventListener("pointerdown", function (e) {
      if (e.pointerType === "mouse" && e.button !== 0) return;
      try { box.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
      var p = rel(e);
      ptrs[e.pointerId] = p;
      if (count() === 1) tap = { t: Date.now(), x: p.x, y: p.y, ok: true, last: tap.last, lx: tap.lx, ly: tap.ly };
      else tap.ok = false;
      startGesture();
    });
    box.addEventListener("pointermove", function (e) {
      if (!ptrs[e.pointerId] || !gesture) return;
      var p = rel(e);
      ptrs[e.pointerId] = p;
      if (tap.ok && Math.hypot(p.x - tap.x, p.y - tap.y) > 10) tap.ok = false;
      if (gesture.kind === "pan") {
        if (tap.ok) return;
        fit = false;
        v = { s: v.s, x: gesture.vx + p.x - gesture.sx, y: gesture.vy + p.y - gesture.sy };
        apply();
      } else {
        var a = ptrs[gesture.id0], b = ptrs[gesture.id1];
        if (!a || !b) return;
        var d = Math.hypot(a.x - b.x, a.y - b.y);
        var c = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        var g = gesture;
        var ns = clamp((g.v0.s * d) / g.d0, IMG_MIN, IMG_MAX);
        fit = false;
        v = { s: ns, x: c.x - ((g.c0.x - g.v0.x) * ns) / g.v0.s, y: c.y - ((g.c0.y - g.v0.y) * ns) / g.v0.s };
        apply();
      }
    });
    function up(e) {
      if (!ptrs[e.pointerId]) return;
      var p = ptrs[e.pointerId];
      delete ptrs[e.pointerId];
      if (e.type === "pointerup" && tap.ok && count() === 0 && Date.now() - tap.t < 300) {
        var now = Date.now();
        if (now - tap.last < 320 && Math.hypot(p.x - tap.lx, p.y - tap.ly) < 40) {
          tap.last = 0;
          if (fit || Math.abs(v.s - fitScale()) < 0.001) { fit = false; place(1); } else { fit = true; layout(); }
        } else { tap.last = now; tap.lx = p.x; tap.ly = p.y; }
      }
      tap.ok = false;
      startGesture(); // 捏合抬起一指后，剩下的一指接着平移
    }
    box.addEventListener("pointerup", up);
    box.addEventListener("pointercancel", up);

    root.appendChild(tools);
    root.appendChild(box);
    return root;
  }

  /* ───────────────────────── 音频 / 视频 ───────────────────────── */

  function buildMedia(tag, url, label) {
    var wrapCls = tag === "audio" ? "rd-audio-root" : "rd-video-root";
    var root = h("div", { class: wrapCls });
    var el = h(tag, { class: tag === "audio" ? "rd-audio" : "rd-video", src: url, controls: true, preload: "metadata" });
    if (tag === "video") el.setAttribute("playsinline", "");
    el.addEventListener("error", function () {
      root.textContent = "";
      root.appendChild(h("div", { class: "rd-error", text: label + "无法播放：" + mediaErrorDetail(el.error) }));
    });
    root.appendChild(el);
    return root;
  }

  /* ───────────────────────── HTML iframe ───────────────────────── */

  function buildHtmlFrame() {
    var wrap = h("div", { class: "rd-frame-wrap" });
    var frame = h("iframe", {
      class: "rd-frame", title: state.doc.name,
      src: state.allowJs ? "/doc-html?js=1" : "/doc-html",
      // 永远不加 allow-same-origin：页面是不透明源，碰不到本页与原生侧
      sandbox: state.allowJs ? "allow-scripts allow-popups" : "allow-popups",
      referrerpolicy: "no-referrer",
    });
    function sz() {
      var z = state.zoom;
      frame.style.width = 10000 / z + "%";
      frame.style.height = 10000 / z + "%";
      frame.style.transform = "scale(" + z / 100 + ")";
    }
    sz();
    zoomHooks.push(sz);
    wrap.appendChild(frame);
    return wrap;
  }

  /* ───────────────────────── 主渲染 ───────────────────────── */

  function showMessage(cls, text) {
    var app = document.getElementById("app");
    app.textContent = "";
    app.appendChild(h("div", { class: cls, text: text }));
  }

  function render() {
    var d = state.doc;
    var app = document.getElementById("app");
    zoomHooks = [];
    app.textContent = "";

    var isImage = d.kind === "image", isAudio = d.kind === "audio", isVideo = d.kind === "video";
    var isHtml = d.kind === "html", isCode = d.kind === "code";
    var hasSource = d.kind === "markdown" || isHtml || (isImage && isSvg(d.name));
    var mode = hasSource ? state.mode : "render";

    // 顶栏
    var meta = h("span", { class: "rd-meta" }, [fmtSize(d.bytes.length)]);
    if (isImage) meta.appendChild(h("span", { id: "rd-meta-img", text: state.imgInfo ? " · " + state.imgInfo : "" }));
    else if (!isAudio && !isVideo) meta.appendChild(document.createTextNode(" · " + d.enc));

    var ctl = h("div", { class: "rd-ctl" });
    if (isHtml && mode === "render") {
      ctl.appendChild(h("label", { class: "rd-chip" + (state.allowJs ? " on" : ""), title: "页面在隔离沙箱中运行，无法访问本应用；只在信任该文件时开启" }, [
        h("input", { type: "checkbox", checked: state.allowJs, onchange: function (e) { state.allowJs = e.target.checked; render(); } }),
        "允许脚本",
      ]));
    }
    if (hasSource) {
      ctl.appendChild(h("div", { class: "rd-seg" }, [
        h("button", { class: mode === "render" ? "on" : "", text: isImage ? "图像" : "渲染", onclick: function () { state.mode = "render"; render(); } }),
        h("button", { class: mode === "source" ? "on" : "", text: "源码", onclick: function () { state.mode = "source"; render(); } }),
      ]));
    }
    if (!((isImage || isAudio || isVideo) && mode === "render")) {
      var zv = h("button", { class: "rd-zoom-val", title: "还原", text: state.zoom + "%", onclick: function () { setZoom(100); } });
      ctl.appendChild(h("div", { class: "rd-zoom" }, [
        h("button", { text: "−", "aria-label": "缩小", onclick: function () { setZoom(state.zoom - 10); } }),
        zv,
        h("button", { text: "+", "aria-label": "放大", onclick: function () { setZoom(state.zoom + 10); } }),
      ]));
      zoomHooks.push(function () { zv.textContent = state.zoom + "%"; });
    }
    var bar = h("div", { class: "rd-bar" }, [
      h("div", { class: "rd-id" }, [
        h("span", { class: "rd-badge rd-" + d.kind, text: kindLabel(d.name, d.kind) }),
        h("span", { class: "rd-source", title: d.source }, [h("bdi", { text: d.source })]),
        meta,
      ]),
      ctl.childNodes.length ? ctl : null,
    ]);

    // 正文
    var body = h("div", { class: "rd-body", id: "rd-body" });
    if (mode === "source") {
      var src = h("pre", { class: "rd-src", text: d.text });
      var sfs = function () { src.style.fontSize = (13 * state.zoom) / 100 + "px"; };
      sfs();
      zoomHooks.push(sfs);
      body.appendChild(src);
    } else if (isImage) {
      body.appendChild(buildImage(blobUrl(imageMime(d.name))));
    } else if (isAudio) {
      body.appendChild(buildMedia("audio", blobUrl(audioMime(d.name)), "音频"));
    } else if (isVideo) {
      body.appendChild(buildMedia("video", blobUrl(videoMime(d.name)), "视频"));
    } else if (isCode) {
      body.appendChild(buildCode(d.text, codeLang(d.name), isJson(d.name)));
    } else if (isHtml) {
      body.appendChild(buildHtmlFrame());
    } else {
      var art = renderMarkdown(d.text);
      var mfs = function () { art.style.fontSize = (15 * state.zoom) / 100 + "px"; };
      mfs();
      zoomHooks.push(mfs);
      body.appendChild(h("div", { class: "rd-scroll" }, [art]));
    }
    app.appendChild(bar);
    app.appendChild(body);
  }

  /* ───────────────────────── 启动 ───────────────────────── */

  async function boot() {
    var meta;
    try {
      var mr = await fetch("/doc-meta", { cache: "no-store" });
      if (!mr.ok) throw new Error("HTTP " + mr.status);
      meta = await mr.json();
      if (meta && meta.error) throw new Error(meta.error);
      if (!meta || typeof meta.name !== "string") throw new Error("元数据无效");
      var kind = meta.kind || viewableKind(meta.name);
      if (!kind) throw new Error("不支持的文件类型");
      var dr = await fetch("/doc", { cache: "no-store" });
      if (!dr.ok) throw new Error("HTTP " + dr.status);
      var bytes = new Uint8Array(await dr.arrayBuffer());
      var d = { name: meta.name, source: meta.source || meta.name, kind: kind, bytes: bytes, text: "", enc: "" };
      // 图片（SVG 源码视图除外）/ 音频 / 视频是二进制，不解码成文本；SVG 解码仅为源码视图
      if (kind !== "audio" && kind !== "video" && (kind !== "image" || isSvg(d.name))) {
        var dec = decode(bytes);
        d.text = dec.text;
        d.enc = dec.enc;
      }
      state.doc = d;
      document.title = "浏览 · " + d.name;
      render();
    } catch (e) {
      showMessage("rd-error", "无法打开：" + (e && e.message ? e.message : String(e)));
      throw e;
    }
  }

  window.NXViewer = {
    ready: boot().then(function () { return true; }, function (e) { return false; }),
    helpers: {
      viewableKind: viewableKind, codeLang: codeLang, kindLabel: kindLabel, imageMime: imageMime,
      audioMime: audioMime, videoMime: videoMime, isSvg: isSvg, isJson: isJson, decode: decode,
      mediaErrorDetail: mediaErrorDetail,
    },
  };
})();
