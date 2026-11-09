// 「浏览」用的取词：从 xterm 缓冲区里取出落点所在的「词」。桌面版 TerminalView.tsx
// `wordAtPoint` 的移植，区别有两处：
//   1. 按**格**走而不是按字符串下标走：宽字符（中文）占两格，右半格 getWidth() 为 0，
//      桌面版「1 字符 = 1 列」换算会让落点错位，这里没有这个问题；
//   2. 软折行的几行（isWrapped）当成一条逻辑行：手机终端只有三四十列，稍长的路径一定
//      折行，只看落点所在的那一行会把路径从折行处切断。
//
// 写成不依赖 DOM / xterm 全局的纯函数，用 `buf` 的最小接口（length、getLine(y) →
// {isWrapped, getCell(x) → {getChars(), getWidth()}}），好在宿主机上用 node + 桩缓冲区测。
// 「这个词是不是可浏览文件」由 Kotlin 判断（ui/RemoteViewPath.kt），这里只负责取词。
(function (root) {
  // 与桌面版一致：空白和 ()[]{}<>。引号、尾随标点留给 Kotlin 侧清理。
  var BOUNDARY = /[\s()\[\]{}<>]/;
  // 折行向上 / 向下各最多追这么多行，防一条没有换行的巨型输出拖慢菜单。
  var MAX_WRAP_ROWS = 40;

  /**
   * row / col 是缓冲区绝对行号和列号；cols 是终端列数。落点不在词里
   * （空白、分隔符、行尾之后）返回 null。
   */
  function wordAt(buf, row, col, cols) {
    if (!buf || row < 0 || col < 0 || col >= cols || !buf.getLine(row)) return null;

    var first = row;
    while (first > 0 && row - first < MAX_WRAP_ROWS && buf.getLine(first) && buf.getLine(first).isWrapped) first--;
    var last = row;
    while (last + 1 < buf.length && last - row < MAX_WRAP_ROWS) {
      var next = buf.getLine(last + 1);
      if (!next || !next.isWrapped) break;
      last++;
    }

    // 每个「单元」是一个字符（宽字符一个单元，右半格不单独成元）。
    var units = [];
    var hit = -1;
    for (var r = first; r <= last; r++) {
      var line = buf.getLine(r);
      if (!line) continue;
      for (var c = 0; c < cols; c++) {
        var cell = line.getCell(c);
        if (!cell) continue;
        if (cell.getWidth() === 0) {
          // 宽字符的右半格：落点在这里就算在左半格的字符上。
          if (r === row && c === col && units.length) hit = units.length - 1;
          continue;
        }
        var ch = cell.getChars();
        if (ch === "") {
          // 折行行尾放不下宽字符时留下的空格子不是真空格，跳过，别把词从这里断开。
          if (c === cols - 1 && r < last) continue;
          ch = " ";
        }
        if (r === row && c === col) hit = units.length;
        units.push(ch);
      }
    }
    if (hit < 0 || BOUNDARY.test(units[hit])) return null;

    var a = hit;
    var b = hit;
    while (a > 0 && !BOUNDARY.test(units[a - 1])) a--;
    while (b < units.length - 1 && !BOUNDARY.test(units[b + 1])) b++;
    return units.slice(a, b + 1).join("") || null;
  }

  var api = { wordAt: wordAt };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.NexusViewTarget = api;
})(typeof window !== "undefined" ? window : this);
