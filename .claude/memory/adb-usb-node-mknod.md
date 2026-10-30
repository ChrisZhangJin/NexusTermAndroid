---
name: adb-usb-node-mknod
description: "adb sees no devices in this container until the phone's /dev/bus/usb node is recreated by hand with mknod"
metadata: 
  node_type: memory
  type: project
  originSessionId: 323d9a20-4dfb-4370-95a6-f6b681a66926
  modified: 2026-10-30T02:31:27.206Z
---

`adb devices` returning an empty list in this container almost never means the
phone is disconnected. `/dev/bus/usb` is a **static bind mount** from the host —
it does not track hot-plug, so a phone that re-enumerates (replug, reboot, USB
mode change, container restart) gets a new `devnum` with no matching device node
inside the container, and libusb can't open it.

Fix — read the real devnum from sysfs (which *is* live), then create the node:

```bash
for d in /sys/bus/usb/devices/*/; do
  [ -f "$d/devnum" ] && printf "%s bus=%s dev=%s %s:%s %s\n" "$(basename $d)" \
    "$(cat $d/busnum)" "$(cat $d/devnum)" "$(cat $d/idVendor)" \
    "$(cat $d/idProduct)" "$(cat $d/product 2>/dev/null)"
done
# minor = (busnum - 1) * 128 + devnum - 1 ; major is always 189
mknod /dev/bus/usb/00<BUS>/0<DEVNUM> c 189 <minor> && chmod 666 ...
adb kill-server && adb devices -l
```

Stale nodes from previous plugs are harmless, leave them. After this the device
usually shows as `unauthorized` — the USB-debugging RSA prompt has to be accepted
on the phone's screen before it flips to `device`.

**Why:** costs ~15 minutes every time if you instead go hunting for a cable, a
driver, or a developer-options toggle. The container is the problem, not the phone.

**How to apply:** whenever `adb devices` is empty, run the sysfs scan *first*
before suggesting anything to the user. See [[test-devices]].
