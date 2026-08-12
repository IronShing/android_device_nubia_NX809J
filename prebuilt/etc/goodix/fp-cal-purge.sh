#!/system/bin/sh
# One-shot migration, run from restore-fp-cal.rc at post-fs-data.
#
# Builds before 2026-08-06 copied the build machine's own /data/vendor/goodix files into
# every phone (see restore-fp-cal.rc). Those are runtime base images belonging to a
# different sensor, and they are what kept UDFPS from working for everyone else. The real
# per-unit calibration lives on /mnt/vendor/persist and is untouched by this.
#
# Delete only the files those builds shipped, so the Goodix HAL regenerates its own from
# this phone's persist cal. Enrolled templates (finger_*.so) and auth_token_0.so are left
# alone. Runs once; the marker makes every later boot a no-op.

MARKER=/data/vendor/goodix/.rom_cal_purged
DIR=/data/vendor/goodix

[ -f "$MARKER" ] && exit 0
[ -d "$DIR" ] || exit 0

for f in cali_0_0.so cali_0_1.so cali_0_2.so cali_0_3.so \
         cali_3.so cali_4.so sys_cached_f_params_0.so sys_cached_params_0.so; do
    rm -f "$DIR/$f"
done

: > "$MARKER"
/system/bin/chown system:system "$MARKER"
/system/bin/chmod 0600 "$MARKER"
/system/bin/restorecon "$MARKER" 2>/dev/null
/system/bin/log -t fp_cal_purge "removed pre-2026-08 shipped goodix cal; HAL will regenerate"
exit 0
