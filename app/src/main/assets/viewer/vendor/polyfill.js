// marked 18 uses Array.prototype.at (Chrome 92+) and Object.hasOwn (Chrome 93+);
// system WebViews on older Android (e.g. Chrome 87 on Android 10) lack them.
if (!Array.prototype.at) {
  Object.defineProperty(Array.prototype, "at", {
    value: function (n) {
      n = Math.trunc(n) || 0;
      if (n < 0) n += this.length;
      return this[n];
    },
    writable: true,
    configurable: true,
  });
}
if (!Object.hasOwn) {
  Object.hasOwn = function (o, k) {
    return Object.prototype.hasOwnProperty.call(o, k);
  };
}
