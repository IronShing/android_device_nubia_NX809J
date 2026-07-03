#!/bin/bash
# build_super_ci.sh — assemble the NX809J flashable super.img for CI.
#
# HYBRID by design (see device/nubia/NX809J/.github/README-CI.md "Audit"):
#   FROM SOURCE  (this build, reproducible): system, system_ext, product
#   MANUAL/STOCK (not source-reproducible yet): vendor (stock B13MR + enforcing CIL
#                + ultrawide .so patch), odm (curated), vendor_dlkm + system_dlkm (stock).
# Geometry is the fixed NX809J super layout (build_super_v37.sh lineage).
#
# All inputs overridable via env so the workflow can point at signed images.
set -euo pipefail

LINEAGE="${LINEAGE:-/var/home/beast/android/lineage}"
OUT="${OUT:-$LINEAGE/out/target/product/NX809J}"
BIN="${BIN:-$LINEAGE/out/host/linux-x86/bin}"
SCRATCH="${SCRATCH:-/var/home/beast/lineage-scratch}"

# --- source partitions (default = fresh build output; override to signed dir) ---
SYS="${SYS:-$OUT/system.img}"
SEXT="${SEXT:-$OUT/system_ext.img}"
PROD="${PROD:-$OUT/product.img}"
# --- non-source (stable, hand-built) inputs ---
VEN="${VEN:-$SCRATCH/uw_super/vendor_uw.img}"          # stock vendor + enforcing CIL + ultrawide .so patch
ODM="${ODM:-$SCRATCH/uw_super/odm_uw.img}"             # curated odm
VDLKM="${VDLKM:-$SCRATCH/ci_inputs/vendor_dlkm.img}"   # stock dlkm (matches WildKernels kernel modules)
SDLKM="${SDLKM:-$SCRATCH/ci_inputs/system_dlkm.img}"

OUTDIR="${OUTDIR:-$SCRATCH/super_ci}"; mkdir -p "$OUTDIR"
OUTIMG="${OUTIMG:-$OUTDIR/super.img}"

command -v "$BIN/lpmake" >/dev/null || { echo "ERROR: lpmake not found in $BIN (build host tools first: 'm' or 'm lpmake')"; exit 1; }
for f in "$SYS" "$SEXT" "$PROD" "$VEN" "$ODM" "$VDLKM" "$SDLKM"; do
  [ -f "$f" ] || { echo "ERROR: missing super input: $f"; exit 1; }
done
sz(){ s=$(stat -c%s "$1"); echo $(( (s+4095)/4096*4096 )); }

echo "== NX809J super inputs =="
printf "  %-14s %s\n" system      "$SYS ($(du -h "$SYS"|cut -f1), SOURCE)"
printf "  %-14s %s\n" system_ext  "$SEXT ($(du -h "$SEXT"|cut -f1), SOURCE)"
printf "  %-14s %s\n" product     "$PROD ($(du -h "$PROD"|cut -f1), SOURCE)"
printf "  %-14s %s\n" vendor      "$VEN ($(du -h "$VEN"|cut -f1), STOCK+enf+uw)"
printf "  %-14s %s\n" odm         "$ODM ($(du -h "$ODM"|cut -f1), curated)"
printf "  %-14s %s\n" vendor_dlkm "$VDLKM ($(du -h "$VDLKM"|cut -f1), stock)"
printf "  %-14s %s\n" system_dlkm "$SDLKM ($(du -h "$SDLKM"|cut -f1), stock)"

"$BIN/lpmake" --metadata-size 65536 --super-name super --metadata-slots 3 \
  --device super:19327352832 --block-size 4096 --virtual-ab \
  --group qti_dynamic_partitions_a:19323158528 --group qti_dynamic_partitions_b:19323158528 \
  --partition system_a:readonly:$(sz "$SYS"):qti_dynamic_partitions_a       --image system_a="$SYS" \
  --partition vendor_a:readonly:$(sz "$VEN"):qti_dynamic_partitions_a       --image vendor_a="$VEN" \
  --partition vendor_dlkm_a:readonly:$(sz "$VDLKM"):qti_dynamic_partitions_a --image vendor_dlkm_a="$VDLKM" \
  --partition product_a:readonly:$(sz "$PROD"):qti_dynamic_partitions_a     --image product_a="$PROD" \
  --partition system_ext_a:readonly:$(sz "$SEXT"):qti_dynamic_partitions_a  --image system_ext_a="$SEXT" \
  --partition system_dlkm_a:readonly:$(sz "$SDLKM"):qti_dynamic_partitions_a --image system_dlkm_a="$SDLKM" \
  --partition odm_a:readonly:$(sz "$ODM"):qti_dynamic_partitions_a          --image odm_a="$ODM" \
  --partition system_b:readonly:0:qti_dynamic_partitions_b \
  --partition vendor_b:readonly:0:qti_dynamic_partitions_b \
  --partition vendor_dlkm_b:readonly:0:qti_dynamic_partitions_b \
  --partition product_b:readonly:0:qti_dynamic_partitions_b \
  --partition system_ext_b:readonly:0:qti_dynamic_partitions_b \
  --partition system_dlkm_b:readonly:0:qti_dynamic_partitions_b \
  --partition odm_b:readonly:0:qti_dynamic_partitions_b \
  --sparse --output "$OUTIMG"

echo "OUTPUT: $OUTIMG  ($(du -h "$OUTIMG"|cut -f1) sparse)"
