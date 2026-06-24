#!/bin/bash
# Build a NATIVE-enforcing vendor.img (+ super) for NX809J.
# Approach (VALIDATED 2026-06-24): take the known-booting STOCK vendor, append our
# enforcing rules (CIL) to /vendor/etc/selinux/vendor_sepolicy.cil, repack via the
# build's own build_image.py (correct labels), lpmake into super. init recombines
# the rules at boot -> native enforcing, NO KSU module. secilc-validated, boots clean,
# setenforce 1 -> Enforcing with 0 critical denials. XDA flash method UNCHANGED.
set -uo pipefail
LIN=/var/home/beast/android/lineage
ED=$LIN/device/nubia/NX809J/enforcing
STOCKV=/tmp/stock_vendor_list                 # stock vendor extracted (fsck.erofs --extract vendor_a.img)
OUT=$LIN/out/target/product/NX809J
WK=/var/home/beast/lineage-scratch/vendor_enf; rm -rf "$WK"; cp -a "$STOCKV" "$WK"
cat "$WK/etc/selinux/vendor_sepolicy.cil" "$ED/native_enforcing_rules.cil" > "$WK/etc/selinux/vendor_sepolicy.cil.new"
mv "$WK/etc/selinux/vendor_sepolicy.cil.new" "$WK/etc/selinux/vendor_sepolicy.cil"
export PATH="$LIN/out/host/linux-x86/bin:$PATH"
mkdir -p /var/home/beast/lineage-scratch/vendor_enf_dir
python3 $LIN/build/make/tools/releasetools/build_image.py "$WK" \
  "$OUT/obj/PACKAGING/vendor_intermediates/vendor_image_info.txt" \
  /var/home/beast/lineage-scratch/vendor_enf_dir/vendor.img "$OUT"
echo "vendor_enf.img built -> lpmake into super with build_super_v20_enf.sh"
