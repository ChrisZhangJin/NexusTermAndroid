---
name: test-devices
description: "The two physical phones used to test NexusTerm, and why the API 29 vs 36 gap matters"
metadata: 
  node_type: memory
  type: project
  originSessionId: 323d9a20-4dfb-4370-95a6-f6b681a66926
  modified: 2026-10-30T02:31:35.696Z
---

NexusTerm is tested on real hardware, not an emulator. Two phones as of
2026-10-30:

| | Xiaomi MI 8 SE | HONOR PTP-AN00 |
|---|---|---|
| serial | `fbe7c2e6` | `A9SE025507001291` |
| OS | MIUI, Android 10 | MagicOS 10.0.0.175, Android 16 |
| API | **29** | **36** |
| screen | 1080x2244, density 440 | 1264x2800 @560, overridden to 948x2100 @420 |
| ABI | arm64-v8a | arm64-v8a (same, no Rust rebuild needed) |

The API gap is the point — several deliberate decisions in this app branch on
SDK level and were only ever exercised on 29:

- foreground service `specialUse` type only takes effect on API 34+
- key bar IME detection falls back to the `WindowInsetsCompat$Impl20` heuristic
  below API 30; 36 uses the real framework implementation
- `adjustResize` behaves differently from API 30 up
- the MIUI three-finger-screenshot touch steal is Xiaomi-specific and may not
  reproduce on MagicOS

Always say which phone a test result came from — "it works" on one proves
little about the other.

**Why:** a pass on the MI 8 SE is not a pass on modern Android, and vice versa.

**How to apply:** when a gesture/insets/notification change lands, test on both,
or state explicitly which one was skipped. Getting a device visible at all may
need [[adb-usb-node-mknod]].
