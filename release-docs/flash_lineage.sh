#!/bin/bash
# Flash LineageOS 23.2 to the NX809J via EDL/qdl. Run from the package root
# (the folder containing images/ loaders/ tools/). Device must be in EDL (9008):
#   adb reboot edl    (or: power off, hold Vol-Up+Vol-Down, plug USB)
#
# Flashes the boot chain (LUN4) + super (LUN0) AND wipes /data + /metadata.
# The /data wipe is REQUIRED when coming from stock: stale stock encryption on
# /data makes the first LOS boot hang at the boot animation (adb comes up but it
# never reaches the setup wizard). The bundled recovery can't format /data, so we
# erase userdata+metadata here via EDL — no recovery needed.
set -uo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
QDL="$HERE/tools/qdl"
LD="$HERE/loaders"
IMG="$HERE/images"

[ -x "$QDL" ] || chmod +x "$QDL" 2>/dev/null
if [ ! -f "$IMG/super.img" ]; then
  if [ -f "$IMG/super.img.zst" ]; then
    echo "Decompressing super.img.zst (~19 GB out)..."
    zstd -d "$IMG/super.img.zst" -o "$IMG/super.img"
  else
    echo "ERROR: no super.img or super.img.zst in images/"; exit 1
  fi
fi
lsusb 2>/dev/null | grep -q '05c6:9008' || { echo "ERROR: device not in EDL (9008). Run 'adb reboot edl' first."; exit 1; }

echo "EDL detected — flashing boot chain + super (~8-10 min, do not unplug)..."
"$QDL" --storage ufs --oem ZTE --include "$LD" "$LD/prog_firehose_ddr.elf" \
  write 4/boot_a          "$IMG/boot.img" \
  write 4/init_boot_a     "$IMG/init_boot.img" \
  write 4/vendor_boot_a   "$IMG/vendor_boot.img" \
  write 4/dtbo_a          "$IMG/dtbo.img" \
  write 4/vbmeta_a        "$IMG/vbmeta.img" \
  write 0/vbmeta_system_a "$IMG/vbmeta_system.img" \
  write 0/super           "$IMG/super.img" \
  erase 0/userdata \
  erase 0/metadata \
  && echo "DONE. Long-press Power ~10s to boot. First boot ~1-2 min (formats /data)." \
  || { echo "qdl failed rc=$?"; exit 1; }
