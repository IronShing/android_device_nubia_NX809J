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

# ============================================================================
# DISPLAY HALs — ALL-STOCK (2026-07-11). The source composer + stock SDM hybrid
# is impossible: the ABI seam (source libsdmclient::GetExtendedDisplay needs
# source libsdmutils; stock libsdmdal is the ONLY DAL that drives SM8850 SDE)
# SIGILLs the composer. So ship the STOCK composer/allocator/demura binaries
# (cc_prebuilt_binary prefer:true in Android.bp) coherent with the stock SDM
# (libsdmcore/dal/utils/client prefer:true) + stock snapalloc-impl. The binaries'
# DT_NEEDED interface libs (composer3-V4, common-V2, mapper@*, ...) build FROM
# SOURCE and are pulled via their shared_libs; the 3 versions with NO source
# module (aiqe-V3, composer3-V1, config-V13) are prebuilt in Android.bp.
#
# composer_version=stock DISABLES the qcom-caf source composer module (see its
# Android.bp enabled: gate). The source composer can't coexist with stock SDM libs
# (it would link both config-V12/aiqe-V2/composer3-V4 from source AND config-V13/
# aiqe-V3/composer3-V1 from the stock libsdm* -> "multiple versions of the same
# aidl_interface"). We ship the stock composer binary (prefer:true prebuilt) instead.
$(call soong_config_set,qtidisplay,composer_version,v3_4)
$(call soong_config_set,qtidisplay,default,true)
PRODUCT_PACKAGES += \
    vendor.qti.hardware.display.allocator-service \
    vendor.qti.hardware.display.composer-service \
    vendor.qti.hardware.display.demura-service

# snap allocator impl (dlopen'd by composer/allocator GrallocSnapHelper) — fixes SF createClient stall
PRODUCT_PACKAGES += \
    vendor.qti.hardware.display.snapalloc-impl

# ============================================================================
# (superseded) STOCK PREBUILT @4 stack notes retained below for history.
# DISPLAY HALs — use the STOCK PREBUILT @4 stack (not source).
# The qcom-caf/sm8750 composer source maxes out at composer3 @3 (v3_3: its V4 path
# is an unimplemented abstract AidlComposerClient), and Android 16's framework compat
# matrix (FCM 202504) requires composer3 >= @4. So a clean m dist source-build fails
# check_vintf: "composer3@3 is deprecated; requires at least 4". The stock prebuilt
# composer/allocator/demura advertise @4 and match the validated super runtime, so we
# ship those (prebuilt binaries + init.rc + @4 vintf fragment + coherent QTI lib stack,
# all defined in vendor/nubia/NX809J/Android.bp). The prebuilts live in the nubia soong
# namespace, so their lib names don't collide with the qcom-caf source modules.
# NOTE (2026-07-06): allocator + demura build FROM SOURCE (they ship their own init .rc +
# vintf and their versions satisfy the FCM). ONLY composer is a same-name prefer:true
# prebuilt in Android.bp (source composer is composer3 @2; Android 16 requires @4). The
# earlier all-prebuilt trio (prefer:true) stripped allocator/demura's .rc -> composer's
# display never armed -> boot hang at logo. Requesting the canonical names here: composer
# resolves to the @4 prebuilt (prefer), allocator/demura resolve to source.
# ALL THREE display services = pure source (coherent with the source libsdm* stack, which
# exports sdm::IsExtendedRange). composer_version=v4 pins the source composer to the new v4
# AIDL impl (v3_3 methods getDisplayConfigurations/notifyExpectedPresent PLUS the composer3 @4
# delta getLuts/getMaxLayerPictureProfiles/startHdcpNegotiation added in
# hardware/qcom-caf/sm8750/display/hal/composer). This makes AidlComposerClient a concrete @4
# implementation so it satisfies FCM 202504 (composer3 >= @4) and no longer needs the stock
# prebuilt composer — the whole display trio is now source-coherent (fixes the runtime
# "createClient: Unable to get snap helper" ABI seam from mixing stock @4 composer + source alloc).
# (history) The source-composer build (composer_version=v4 + source composer/allocator/
# demura) is removed — the stock binaries above supersede it. Interface libs that DO have
# a source module (composer3-V4, common-V2, mapper@*, ...) must NOT be prebuilt (vendor-only
# prebuilt collides: "partition is different"); only the 3 versions with no source module
# (aiqe-V3, composer3-V1, config-V13) are prebuilt in Android.bp.

# ============================================================================
# Missing vendor libs (2026-07-12): boot reached zygote+system_server (display +
# HintManagerService fixes) but a tail of frozen-aidl -ndk libs + the audio core
# HAL impl were never pulled into /vendor, so their consumers crash-loop at dlopen:
#   libaudiocorehal.default        -> audiohalservice.qti -> audioserver SIGSEGV ->
#                                     AudioService onAudioServerDied loop (boot blocker)
#   android.hardware.boot-V1-ndk   -> android.hardware.boot-service.qti (CANNOT LINK)
#   android.hardware.thermal-V3-ndk-> android.hardware.thermal-service.qti (CANNOT LINK)
#   display.config-V2-ndk          -> camx.device-impl.so -> camera provider (CANNOT LINK)
#   graphics.common-V5-ndk         -> libqcodec2_core.so -> codec2 media service
# All 4 -ndk are frozen source versions (ABI-identical to stock); source-built vendor
# variants install to /vendor/lib64. No prebuilt -> no "partition is different" collision.
PRODUCT_PACKAGES += \
    android.hardware.boot-V1-ndk \
    android.hardware.thermal-V3-ndk \
    android.hardware.graphics.common-V5-ndk \
    vendor.qti.hardware.display.config-V2-ndk
