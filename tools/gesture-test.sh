#!/usr/bin/env bash
# Multi-touch gesture harness for testing NexusTerm on a physical device.
#
# WHY THIS EXISTS
#   `adb shell input` can only ever build a single-pointer MotionEvent, and
#   NexusTerm's terminal gestures (ui/TerminalTabsPager.kt) are mostly two- and
#   three-finger.  The obvious fallback -- `sendevent` on the raw touchscreen --
#   is blocked on a production MIUI build: /dev/input/eventN is crw-rw---- root
#   input, and although the shell user *is* in the `input` group, SELinux
#   (Enforcing, u:r:shell:s0) denies the open.  `adb root` is refused too.
#
#   But the private API underneath `input` has no pointer-count limit, and the
#   shell UID already holds INJECT_EVENTS -- that is how `input` works at all.
#   So we compile a tiny class that calls
#   InputManager.getInstance().injectInputEvent(MotionEvent, ASYNC) by
#   reflection, dex it, and run it as the shell user through app_process.
#   Arbitrary pointer counts, real timing, no root.
#
#   The whole gesture is fed to one process on stdin, so a 2-second hold or a
#   fling's release velocity is not at the mercy of ~50ms adb round trips.
#
# DEVICE CAVEAT (MIUI / HyperOS)
#   MIUI's own three-finger gestures sit in front of the app: with
#   `settings get system enable_three_gesture` == 1 (设置 > 更多设置 >
#   手势快捷方式 > 三指下滑截屏) SystemUI pilfers the touch stream as soon as a
#   third finger lands, so NexusTerm's three-finger selection extend never sees
#   it -- in any direction, not just downwards.  Before testing swipe3:
#     adb shell settings put system enable_three_gesture 0   # ... and 1 after.
#
# REQUIREMENTS (host)
#   adb, a JDK (javac), and an Android SDK with build-tools/*/d8 plus
#   platforms/android-*/android.jar.  Set ANDROID_HOME if it is not already.
#
# USAGE
#   tools/gesture-test.sh build                    # compile + push (idempotent)
#   tools/gesture-test.sh raw < script             # feed the mini-language
#   tools/gesture-test.sh tap X Y
#   tools/gesture-test.sh hold2 X1 Y1 X2 Y2 MS     # two fingers held still
#   tools/gesture-test.sh pinch CX CY FROM TO MS   # span FROM->TO px, vertical
#   tools/gesture-test.sh drag X Y DX DY MS        # one finger
#   tools/gesture-test.sh fling X Y DX DY MS       # one finger, no settle
#   tools/gesture-test.sh hold1 X Y MS             # one finger held still
#   tools/gesture-test.sh hold1drag X Y MS DX DY   # hold, then drag on
#   tools/gesture-test.sh swipe3 X Y DX DY MS      # three fingers
#   tools/gesture-test.sh swipe2 X Y DX DY MS      # two fingers (bar paging)
#
# MINI-LANGUAGE (see MT.java below)
#   down ID X Y | up ID | set ID X Y | move ID X Y | frame | sleep MS
#   lerp MS [ID X Y]... | tap X Y | cancel | echo TEXT
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
WORK="${MT_WORK:-/tmp/nexusterm-mt}"
JAR="$WORK/mt.jar"
REMOTE=/data/local/tmp/mt.jar

emit_java() {
  mkdir -p "$WORK/src/com/nexusterm/mt"
  cat > "$WORK/src/com/nexusterm/mt/MT.java" <<'JAVA_EOF'
package com.nexusterm.mt;

import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Multi-pointer MotionEvent injector; runs as shell via app_process. */
public final class MT {

    private static final int ASYNC = 0;

    private static Object inputManager;
    private static Method injectMethod;

    private static final LinkedHashMap<Integer, float[]> POINTERS = new LinkedHashMap<>();
    private static long downTime = 0L;

    public static void main(String[] args) throws Exception {
        bindInjector();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            try {
                exec(t);
            } catch (Exception e) {
                System.out.println("ERR " + line + " :: " + e);
            }
        }
        if (!POINTERS.isEmpty()) {
            emit(MotionEvent.ACTION_CANCEL, 0);
            POINTERS.clear();
        }
        System.out.println("DONE");
    }

    private static void exec(String[] t) throws Exception {
        String cmd = t[0];
        if ("down".equals(cmd)) {
            int id = Integer.parseInt(t[1]);
            POINTERS.put(id, new float[]{Float.parseFloat(t[2]), Float.parseFloat(t[3])});
            int idx = indexOf(id);
            if (POINTERS.size() == 1) {
                downTime = SystemClock.uptimeMillis();
                emit(MotionEvent.ACTION_DOWN, 0);
            } else {
                emit(MotionEvent.ACTION_POINTER_DOWN, idx);
            }
        } else if ("up".equals(cmd)) {
            int id = Integer.parseInt(t[1]);
            int idx = indexOf(id);
            if (idx < 0) return;
            if (POINTERS.size() == 1) emit(MotionEvent.ACTION_UP, 0);
            else emit(MotionEvent.ACTION_POINTER_UP, idx);
            POINTERS.remove(id);
        } else if ("set".equals(cmd)) {
            setPos(Integer.parseInt(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]));
        } else if ("move".equals(cmd)) {
            setPos(Integer.parseInt(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]));
            emit(MotionEvent.ACTION_MOVE, 0);
        } else if ("frame".equals(cmd)) {
            emit(MotionEvent.ACTION_MOVE, 0);
        } else if ("sleep".equals(cmd)) {
            Thread.sleep(Long.parseLong(t[1]));
        } else if ("tap".equals(cmd)) {
            float x = Float.parseFloat(t[1]);
            float y = Float.parseFloat(t[2]);
            POINTERS.clear();
            POINTERS.put(0, new float[]{x, y});
            downTime = SystemClock.uptimeMillis();
            emit(MotionEvent.ACTION_DOWN, 0);
            Thread.sleep(60);
            emit(MotionEvent.ACTION_UP, 0);
            POINTERS.clear();
        } else if ("cancel".equals(cmd)) {
            if (!POINTERS.isEmpty()) emit(MotionEvent.ACTION_CANCEL, 0);
            POINTERS.clear();
        } else if ("lerp".equals(cmd)) {
            lerp(t);
        } else if ("echo".equals(cmd)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < t.length; i++) sb.append(t[i]).append(' ');
            System.out.println("MARK " + sb.toString().trim());
        } else {
            System.out.println("ERR unknown cmd " + cmd);
        }
    }

    /** lerp <ms> [<id> <x> <y>]... -- ~60Hz interpolation to the given targets. */
    private static void lerp(String[] t) throws Exception {
        long ms = Long.parseLong(t[1]);
        int n = (t.length - 2) / 3;
        int[] ids = new int[n];
        float[][] from = new float[n][2];
        float[][] to = new float[n][2];
        for (int i = 0; i < n; i++) {
            ids[i] = Integer.parseInt(t[2 + i * 3]);
            float[] cur = POINTERS.get(ids[i]);
            if (cur == null) throw new IllegalStateException("pointer " + ids[i] + " not down");
            from[i][0] = cur[0];
            from[i][1] = cur[1];
            to[i][0] = Float.parseFloat(t[3 + i * 3]);
            to[i][1] = Float.parseFloat(t[4 + i * 3]);
        }
        int steps = Math.max(1, (int) (ms / 16));
        long start = SystemClock.uptimeMillis();
        for (int s = 1; s <= steps; s++) {
            long target = start + (ms * s) / steps;
            long now = SystemClock.uptimeMillis();
            if (target > now) Thread.sleep(target - now);
            float f = (float) s / steps;
            for (int i = 0; i < n; i++) {
                setPos(ids[i], from[i][0] + (to[i][0] - from[i][0]) * f,
                        from[i][1] + (to[i][1] - from[i][1]) * f);
            }
            emit(MotionEvent.ACTION_MOVE, 0);
        }
    }

    private static void setPos(int id, float x, float y) {
        float[] p = POINTERS.get(id);
        if (p == null) throw new IllegalStateException("pointer " + id + " not down");
        p[0] = x;
        p[1] = y;
    }

    private static int indexOf(int id) {
        int i = 0;
        for (Integer k : POINTERS.keySet()) {
            if (k == id) return i;
            i++;
        }
        return -1;
    }

    private static void emit(int action, int pointerIndex) throws Exception {
        int n = POINTERS.size();
        if (n == 0) return;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        int i = 0;
        for (Map.Entry<Integer, float[]> e : POINTERS.entrySet()) {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = e.getKey();
            pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = pp;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            pc.x = e.getValue()[0];
            pc.y = e.getValue()[1];
            pc.pressure = 1f;
            pc.size = 1f;
            pc.touchMajor = 12f;
            pc.touchMinor = 12f;
            coords[i] = pc;
            i++;
        }
        int act = action;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            act = action | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(downTime, now, act, n, props, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        injectMethod.invoke(inputManager, ev, ASYNC);
        ev.recycle();
    }

    private static void bindInjector() throws Exception {
        List<String> tried = new ArrayList<>();
        for (String cls : new String[]{
                "android.hardware.input.InputManager",          // <= Android 13
                "android.hardware.input.InputManagerGlobal"}) { // Android 14+
            try {
                Class<?> c = Class.forName(cls);
                Method gi = c.getDeclaredMethod("getInstance");
                gi.setAccessible(true);
                inputManager = gi.invoke(null);
                injectMethod = c.getMethod("injectInputEvent", InputEvent.class, int.class);
                injectMethod.setAccessible(true);
                return;
            } catch (Throwable e) {
                tried.add(cls + ": " + e);
            }
        }
        throw new IllegalStateException("no injector: " + tried);
    }
}
JAVA_EOF
}

build() {
  emit_java
  local androidjar d8
  androidjar=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort | tail -1)
  d8=$(ls "$SDK"/build-tools/*/d8 2>/dev/null | sort | tail -1)
  [ -n "$androidjar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }
  [ -n "$d8" ] || { echo "no d8 under $SDK/build-tools" >&2; exit 1; }
  rm -rf "$WORK/classes" "$WORK/out"
  mkdir -p "$WORK/classes" "$WORK/out"
  javac -source 8 -target 8 -nowarn -bootclasspath "$androidjar" \
        -d "$WORK/classes" "$WORK/src/com/nexusterm/mt/MT.java" 2>&1 \
    | grep -v 'bootstrap class path' || true
  "$d8" --min-api 26 --output "$WORK/out" --lib "$androidjar" \
        "$WORK"/classes/com/nexusterm/mt/*.class
  rm -f "$JAR"
  (cd "$WORK/out" && zip -q "$JAR" classes.dex)
  "${ADB[@]}" push "$JAR" "$REMOTE" >/dev/null
  echo "harness built and pushed -> $REMOTE"
}

run() { # mini-language on stdin
  [ -f "$JAR" ] || build >&2
  "${ADB[@]}" shell "CLASSPATH=$REMOTE app_process /data/local/tmp com.nexusterm.mt.MT"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  build) build ;;
  raw)   run ;;
  tap)   printf 'tap %s %s\n' "$1" "$2" | run ;;

  # Two fingers down, held perfectly still (no MOVE frames at all, which is
  # what a real still finger produces), then lifted.
  hold2)
    printf 'down 0 %s %s\ndown 1 %s %s\nsleep %s\nup 1\nup 0\n' \
      "$1" "$2" "$3" "$4" "$5" | run ;;

  # Vertical pinch about (CX,CY): span goes FROM -> TO over MS.
  pinch)
    cx=$1 cy=$2 from=$3 to=$4 ms=$5
    printf 'down 0 %s %s\ndown 1 %s %s\nsleep 80\nlerp %s 0 %s %s 1 %s %s\nsleep 80\nup 1\nup 0\n' \
      "$cx" "$((cy - from / 2))" "$cx" "$((cy + from / 2))" \
      "$ms" "$cx" "$((cy - to / 2))" "$cx" "$((cy + to / 2))" | run ;;

  # One finger: press, drag, pause before lifting (kills the fling).
  drag)
    printf 'down 0 %s %s\nsleep 50\nlerp %s 0 %s %s\nsleep 200\nup 0\n' \
      "$1" "$2" "$5" "$(($1 + $3))" "$(($2 + $4))" | run ;;

  # Same but lifts immediately, so index.html sees release velocity.
  fling)
    printf 'down 0 %s %s\nsleep 30\nlerp %s 0 %s %s\nup 0\n' \
      "$1" "$2" "$5" "$(($1 + $3))" "$(($2 + $4))" | run ;;

  hold1)
    printf 'down 0 %s %s\nsleep %s\nup 0\n' "$1" "$2" "$3" | run ;;

  hold1drag)
    printf 'down 0 %s %s\nsleep %s\nlerp 400 0 %s %s\nsleep 150\nup 0\n' \
      "$1" "$2" "$3" "$(($1 + $4))" "$(($2 + $5))" | run ;;

  swipe2)
    x=$1 y=$2 dx=$3 dy=$4 ms=$5
    printf 'down 0 %s %s\ndown 1 %s %s\nsleep 60\nlerp %s 0 %s %s 1 %s %s\nsleep 80\nup 1\nup 0\n' \
      "$x" "$y" "$((x + 160))" "$y" "$ms" \
      "$((x + dx))" "$((y + dy))" "$((x + 160 + dx))" "$((y + dy))" | run ;;

  swipe3)
    x=$1 y=$2 dx=$3 dy=$4 ms=$5
    printf 'down 0 %s %s\ndown 1 %s %s\ndown 2 %s %s\nsleep 60\nlerp %s 0 %s %s 1 %s %s 2 %s %s\nsleep 80\nup 2\nup 1\nup 0\n' \
      "$x" "$y" "$((x + 150))" "$y" "$((x + 300))" "$y" "$ms" \
      "$((x + dx))" "$((y + dy))" "$((x + 150 + dx))" "$((y + dy))" "$((x + 300 + dx))" "$((y + dy))" | run ;;

  *) sed -n '2,45p' "$0"; exit 1 ;;
esac
