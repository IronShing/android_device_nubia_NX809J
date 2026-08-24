#!/bin/bash
# Re-apply NX809J out-of-tree fixes after a `repo sync` (especially `-d` /
# `--force-sync`) resets the shared LOS repos these touch. We don't fork those
# repos, so the fixes live here as patches (this dir is in the forked device tree,
# so it always survives). Idempotent: already-applied patches are skipped.
#
# Mirrors: frameworks/base @ branch nx809j, vendor/lineage @ branch nx809j,
#          packages/apps/CarrierConfig @ branch nx809j-volte-41902.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(cd "$HERE/../../../.." && pwd)"   # lineage source root

apply() {  # $1 = repo path (rel to TOP)   $2 = patch subdir
  local repo="$TOP/$1" pd="$HERE/$2"
  echo "== $1 =="
  [ -d "$repo/.git" ] || { echo "  !! $1 not found"; return; }
  for p in "$pd"/*.patch; do
    [ -e "$p" ] || continue
    local subj; subj="$(sed -n 's/^Subject: \[PATCH[^]]*\] //p' "$p" | head -1)"
    if git -C "$repo" log --oneline -50 --format='%s' | grep -qxF "$subj"; then
      echo "  ok  (already applied) $(basename "$p")"
    elif git -C "$repo" am --keep-cr --3way "$p" >/dev/null 2>&1; then
      echo "  +   applied $(basename "$p")"
    else
      git -C "$repo" am --abort >/dev/null 2>&1
      echo "  !!  FAILED $(basename "$p") — apply by hand"
    fi
  done
}

# Working-tree edits that were never committed upstream-side. They are NOT commits, so
# they are applied with `git apply` rather than `git am` -- turning them into mailbox
# patches would invent an author and a commit message that never existed.
# Idempotent: a clean reverse-apply means it is already in the tree.
applydiff() {  # $1 = repo path (rel to TOP)   $2 = .diff file in this dir
  local repo="$TOP/$1" df="$HERE/$2"
  [ -e "$df" ] || return
  echo "== $1 =="
  [ -d "$repo/.git" ] || { echo "  !! $1 not found"; return; }
  if git -C "$repo" apply --check --reverse "$df" 2>/dev/null; then
    echo "  ok  (already applied) $(basename "$df")"
  elif git -C "$repo" apply "$df" 2>/dev/null || git -C "$repo" apply --3way "$df" 2>/dev/null; then
    echo "  +   applied $(basename "$df")"
  else
    echo "  !!  FAILED $(basename "$df") -- apply by hand"
  fi
}

apply frameworks/av                frameworks_av
apply frameworks/base              frameworks_base
apply vendor/lineage               vendor_lineage
apply packages/apps/CarrierConfig  packages_apps_CarrierConfig

# Charge limit: re-home the lineage health HAL odm -> system_ext. This device rides
# the stock /vendor and ships a stock-derived /odm, so a device_specific HAL is
# overwritten and never reaches the phone; the device sepolicy labels it on
# /system_ext. Mirror: IronShing/android_hardware_lineage_interfaces @ nx809j.
apply hardware/lineage/interfaces   hardware_lineage_interfaces

# Display: the local_manifest pulls the OnePlus-SM8850 display source (sm8850,
# composer3-V4) to the sm8750/display path; these carry the NX809J port fixes
# (SetupAtomic bounds fix, header wiring, libvmmem, namespace). See sm8850 README.
apply hardware/qcom-caf/sm8750/display/core  display_sm8850/core
apply hardware/qcom-caf/sm8750/display/hal   display_sm8850/hal
echo "done."

# --- uncommitted working-tree edits, LINEAGE TREE ONLY -----------------------
#
# OFF BY DEFAULT. These were captured from the LineageOS tree; the EvolutionX tree
# does not have them and builds fine without them. This script lives in the shared
# device tree, so running it from an EvoX checkout would otherwise apply LineageOS's
# edits to EvoX -- which is exactly what happened once during authoring, dirtying 12
# repos that had to be reverted by hand.
#
# They are kept here so a `repo sync` cannot destroy them, and so the port is
# reproducible. Apply deliberately, from a LineageOS tree only:
#
#     APPLY_WORKTREE_DIFFS=1 bash device/nubia/NX809J/patches/apply-patches.sh
#
if [ "${APPLY_WORKTREE_DIFFS:-0}" = "1" ]; then
  applydiff vendor/nubia/NX809J                    vendor_nubia_NX809J.diff
  applydiff hardware/qcom-caf/common               hardware_qcom-caf_common.diff
  applydiff device/qcom/sepolicy_vndr/sm8750       device_qcom_sepolicy_vndr_sm8750.diff
  applydiff vendor/lineage                         vendor_lineage.diff
  applydiff vendor/qcom/opensource/libvmmem        vendor_qcom_opensource_libvmmem.diff
  applydiff system/sepolicy                        system_sepolicy.diff
  applydiff frameworks/native                      frameworks_native.diff
  applydiff frameworks/libs/systemui               frameworks_libs_systemui.diff
  applydiff packages/apps/Aperture                 packages_apps_Aperture.diff
  applydiff packages/apps/Launcher3                packages_apps_Launcher3.diff
  applydiff packages/apps/Settings                 packages_apps_Settings.diff
  applydiff packages/apps/DocumentsUI              packages_apps_DocumentsUI.diff
  applydiff packages/apps/PrivateSpace             packages_apps_PrivateSpace.diff
  applydiff packages/apps/Evolver                  packages_apps_Evolver.diff
  applydiff packages/apps/Glimpse                  packages_apps_Glimpse.diff
  applydiff packages/providers/MediaProvider       packages_providers_MediaProvider.diff
  applydiff prebuilts/misc                         prebuilts_misc.diff
  applydiff tools/metalava                         tools_metalava.diff
  applydiff art                                    art.diff
else
  echo
  echo "-- skipping working-tree diffs (LineageOS-tree only)."
  echo "   set APPLY_WORKTREE_DIFFS=1 to apply them; see patches/README.md"
fi
