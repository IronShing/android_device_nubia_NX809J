# vendor_hals.mk — Phase A of the native-enforcing built-vendor.img effort.
#
# WHY: the BUILT vendor.img was missing all 9 QTI HAL services because they were
# never in PRODUCT_PACKAGES (they have Soong install rules but nothing pulled
# them in) -> super had to splice STOCK vendor_a.img -> BOARD_VENDOR_SEPOLICY_DIRS
# never reached the device -> permissive-only.
#
# STATUS (2026-06-23): these 6 source-build CLEANLY from the qcom-caf namespaces
# and now land in vendor.img (verified). They pull their lib deps transitively.
PRODUCT_PACKAGES += \
    android.hardware.health-service.qti \
    android.hardware.sensors-service.multihal \
    android.hardware.usb-service.qti \
    audiohalservice.qti \
    vendor.qti.hardware.memtrack-service \
    vendor.qti.hardware.vibrator.service

# DISPLAY HALs — use the STOCK PREBUILT @4 stack (not source).
# The qcom-caf/sm8750 composer source maxes out at composer3 @3 (v3_3: its V4 path
# is an unimplemented abstract AidlComposerClient), and Android 16's framework compat
# matrix (FCM 202504) requires composer3 >= @4. So a clean m dist source-build fails
# check_vintf: "composer3@3 is deprecated; requires at least 4". The stock prebuilt
# composer/allocator/demura advertise @4 and match the validated super runtime, so we
# ship those (prebuilt binaries + init.rc + @4 vintf fragment + coherent QTI lib stack,
# all defined in vendor/nubia/NX809J/Android.bp). The prebuilts live in the nubia soong
# namespace, so their lib names don't collide with the qcom-caf source modules.
PRODUCT_PACKAGES += \
    vendor.qti.hardware.display.allocator-service \
    vendor.qti.hardware.display.composer-service \
    vendor.qti.hardware.display.demura-service

# The composer/allocator/demura AIDL+HIDL interface libs (composer3-V4-ndk, aiqe-V3-ndk,
# config-V13-ndk, mapper@*, mapperextensions@*, etc.) are ABI-versioned and BUILT FROM
# SOURCE (commonsys-intf/display + hardware/interfaces) for both system+vendor variants,
# so we do NOT prebuilt them (a vendor-only prebuilt collides: "partition is different").
# The QTI implementation libs (libsdmcore/utils/client, libgralloc.qti, ...) already have
# prebuilt entries in vendor/nubia/NX809J/Android.bp and install via the normal blob path.
#
# NOTE: no $(call soong_config_set,qtidisplay,composer_version,...) — we no longer
# source-build the composer, so the version pin is intentionally gone.
