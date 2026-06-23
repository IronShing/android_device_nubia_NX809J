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
    vendor.qti.hardware.vibrator.service \
    vendor.qti.hardware.display.allocator-service \
    vendor.qti.hardware.display.composer-service \
    vendor.qti.hardware.display.demura-service

# PENDING — the 3 DISPLAY HALs (composer/allocator/demura) do NOT source-build:
# qcom-caf/sm8750 display source is version-skewed (graphics.composer3 V3<->V4
# abstract-class mismatch + undefined sdm::IsExtendedRange). The display lib stack
# is tightly version-coupled (~15 QTI display AIDL/HIDL libs), so a prebuilt
# composer can't mix with source graphics.composer3-V4. Resolve via EITHER patch
# the CAF display source to V4, OR prebuilt the whole coherent stock display stack.
#   vendor.qti.hardware.display.allocator-service
#   vendor.qti.hardware.display.composer-service
#   vendor.qti.hardware.display.demura-service

# Display HAL version pin: the qcom-caf/sm8750 composer source selects its
# composer3 AIDL version via SOONG_CONFIG_qtidisplay_composer_version, normally
# set by display-product.mk (which this device doesn't inherit). Unset -> no
# version #define/lib -> composer falls through to an unimplemented V4 path
# (abstract AidlComposerClient). v3_3 is the source's max (COMPOSER3_V3 + aiqe-V2).
$(call soong_config_set,qtidisplay,composer_version,v3_3)
