#!/bin/bash
#
# post_sync_fixups.sh — reapply the mechanical, tree-wide edits this port needs.
#
# `repo sync` (especially --force-sync) reverts every uncommitted change in the tree.
# Roughly half the local modifications in this port are not craft — they are two
# systematic transformations applied across many AOSP repos. Keeping those as forks
# would mean maintaining ~29 forks whose entire content is a sed, so they live here
# instead: idempotent, self-documenting, and re-runnable after any sync.
#
# The substantive changes (device tree, vendor blobs, frameworks/base, Dialer, the
# app patches) are NOT handled here — those belong in real forks with real history.
#
# Usage:  tools/post_sync_fixups.sh [/path/to/tree-root]
#         defaults to the tree containing this script.
#
set -uo pipefail

ROOT="${1:-}"
if [ -z "$ROOT" ]; then
  ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
fi
[ -d "$ROOT/build" ] || { echo "!! $ROOT does not look like an Android tree"; exit 1; }
echo "tree: $ROOT"

changed=0

# ---------------------------------------------------------------------------
# 1. min_sdk_version 36 -> 35
#
# Android 16 is API 36, but several mainline modules declare min_sdk_version 36
# while the prebuilt module SDKs available here only go to 35, so Soong fails the
# dependency check. Downgrading the declared minimum is a build-side workaround.
#
# NOTE: this is a workaround, not a fix. If a future sync makes the build succeed
# without it, DELETE this section rather than carrying it forever. Test by running
# a build with SKIP_MINSDK=1 before assuming it is still needed.
# ---------------------------------------------------------------------------
MINSDK_REPOS="
cts
external/conscrypt
frameworks/hardware/interfaces
packages/modules/Bluetooth
packages/modules/common
packages/modules/Connectivity
packages/modules/CrashRecovery
packages/modules/Nfc
packages/modules/Permission
packages/modules/Profiling
packages/modules/Telecom
packages/modules/Telephony
packages/modules/UprobeStats
packages/modules/Uwb
prebuilts/module_sdk/Bluetooth
test/cts-root
"

if [ "${SKIP_MINSDK:-0}" != "1" ]; then
  echo "== min_sdk_version 36 -> 35 =="
  for r in $MINSDK_REPOS; do
    d="$ROOT/$r"
    [ -d "$d" ] || { echo "   -- $r (absent, skipped)"; continue; }
    n=$(grep -rl 'min_sdk_version: "36"' "$d" --include='Android.bp' 2>/dev/null | wc -l)
    if [ "$n" -gt 0 ]; then
      grep -rl 'min_sdk_version: "36"' "$d" --include='Android.bp' 2>/dev/null \
        | xargs -r sed -i 's/min_sdk_version: "36"/min_sdk_version: "35"/g'
      echo "   patched $r ($n file(s))"
      changed=$((changed+1))
    fi
  done
fi

# ---------------------------------------------------------------------------
# 2. Disable legacy-SoC media modules
#
# This port replaces LineageOS's sm8750 display with the OnePlus-SM8850 fork, which
# does not export `display_headers`. The legacy per-SoC media HALs (msm8953 ... sm8250)
# still reference it, so they break the build even though nothing on this device uses
# them. They are disabled rather than fixed: this phone will never run an msm8953
# media HAL.
#
# Structural (whole modules commented out), so it is applied from the stored patches
# rather than regenerated. Patches live alongside this script.
# ---------------------------------------------------------------------------
MEDIA_PATCHES="$(dirname "${BASH_SOURCE[0]}")/post_sync_patches"
if [ -d "$MEDIA_PATCHES" ]; then
  echo "== legacy-SoC media disable =="
  for p in "$MEDIA_PATCHES"/*media.diff; do
    [ -f "$p" ] || continue
    # patches are named by relative path with / replaced by _  (qcom-caf keeps its hyphen)
    rel=$(basename "$p" .diff | sed 's/_/\//g')
    d="$ROOT/$rel"
    [ -d "$d" ] || { echo "   -- $rel (absent, skipped)"; continue; }
    # Detect by our own marker, not by reverse-applying the patch: the two trees carry
    # cosmetically different diffs of the same change, so a reverse-check gives a false
    # "needs regenerating" on whichever tree the patch was not captured from.
    if grep -rq "disabled for NX809J" "$d" --include=*.bp 2>/dev/null; then
      echo "   already applied: $rel"
    elif git -C "$d" apply "$p" 2>/dev/null || git -C "$d" apply --3way "$p" 2>/dev/null; then
      echo "   patched $rel"
      changed=$((changed+1))
    else
      echo "   !! FAILED (upstream moved?): $rel  --  patch needs regenerating"
    fi
  done
else
  echo "== legacy-SoC media disable: no patch dir at $MEDIA_PATCHES"
fi

echo
echo "done — $changed repo(s) modified"
[ "$changed" -eq 0 ] && echo "(nothing to do: tree already fixed up, or upstream no longer needs it)"
