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
