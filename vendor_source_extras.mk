# Boot-critical vendor components that closure.py excludes from prebuilts because
# they are SOURCE-buildable but were never added to PRODUCT_PACKAGES.
# See [[vendor_img_build_native_enforcing]] gap-refresh 2026-07-06.
#
# vndservicemanager: clean AOSP source module (frameworks/native/cmds/servicemanager) —
#   legacy HIDL vendor-service registrar that stock ships. Source-build.
PRODUCT_PACKAGES += \
    vndservicemanager \
    vndservice

# NX809J boot blocker (static-diagnosed 2026-07-06 via vendor.img tree-diff):
# the display composer/allocator/demura HAL BINARIES install (prefer:true prebuilts)
# but their init .rc + VINTF fragments were dropped when prefer:true shadowed the
# qcom-caf source modules -> NO .rc launches the composer -> surfaceflinger blocks
# on IComposer forever -> hang at boot logo, no framework, no USB (matches June sig).
# .rc + vintf now re-attached on the prebuilt blocks in Android.bp. These two extra
# pieces the source tree already builds but nobody pulled:
#   - init.qti.display_boot.{sh,rc}: panel boot oneshot (sm8750 display/hal/init source)
#   - mapper.qti.xml.nx809j: gralloc stable-C mapper @5.0/qti VINTF (mapper.qti.so was
#     present but its fqname unregistered -> gralloc buffer-map failure)
# NOTE (2026-07-14): mapper.qti.xml.nx809j REMOVED — it installed a SECOND copy of
# mapper.qti.xml (as mapper.qti.nx809j.xml), and the source display build already ships
# mapper.qti.xml. Two identical mapper@5.0/qti fragments = VINTF assemble conflict that
# dropped keymint's IKeyMintDevice + boot's IBootControl from the merged manifest ->
# keymint couldn't register -> keystore2/vold hung -> /data mount_all --late hung ->
# boot stuck at logo. Root-caused via kmsg klog capture (see cold_reset_safe_bootmarkers).
PRODUCT_PACKAGES += \
    init.qti.display_boot.sh \
    init.qti.display_boot.rc

# POST-BOOT PARITY TODO (non-boot-critical, deferred): 9 secondary HALs/bins have
# unresolved vendor deps (audit 2026-07-06): boot-control/thermal/SPU-keymint/SPU-weaver
# need vendor-variant AIDL NDK libs (boot-V1/thermal-V3/sharedsecret-V2/weaver-V2 -- the
# AOSP source HAS an orphaned vendor-variant install rule, so pull THAT, not a competing
# prebuilt which collides); cnd/qms/xtra-daemon need libandroid_net.so; test tools
# (sigma_dut/KmCheckKeybox) need libkeystore-wifi-hidl/libvendorsupport. None block boot
# (composer -- the boot gate -- is fixed); they degrade thermal/GPS/connectivity post-boot.
#
# NOTE: libaudiocorehal.{qti,default} / libpalipcservice / libagmipcservice were tried as
# SOURCE (hardware/qcom-caf/sm8750/audio) but the AGM aidlconverter fails to compile —
# missing GSL techpack header (gsl_intf.h not on its header_libs path; classic QTI-audio
# source cascade). Since these are stock QTI binaries, the "as-good-as-stock" path is to
# ship the stock PREBUILTS (remove them from closure.py EXCLUDE_PATHS) rather than fight
# the source header cascade. TODO next iteration.
