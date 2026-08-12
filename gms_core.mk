# De-Googled "core" GApps for the NX809J Minimal variant.
#
# Ships ONLY Google Play Store + GMS Core + Play Services Framework (so Play
# Store works and Play Integrity certifies) plus the GmsCore config blobs
# (default-permissions / sysconfig / preferred-apps). EVERYTHING ELSE is AOSP,
# supplied by the LineageOS base (Trebuchet, Jelly, Etar, DeskClock, Messaging,
# Contacts, AOSP WebView/keyboard, our call-recording Dialer, ...).
#
# Used by evolution_NX809J.mk when NX809J_MINIMAL := true (with WITH_GMS := false
# so common_full_phone.mk does NOT pull a full/mini/pico Google-app tier).
#
# The app packages below are soong modules (vendor/gms/.../Android.mk) that
# install only when listed here; the blob .mk inherits copy config files only.

PRODUCT_PACKAGES += \
    ConfigUpdater \
    Phonesky \
    GoogleServicesFramework \
    PrebuiltGmsCoreVic \
    PrebuiltGmsCoreVic_AdsDynamite \
    PrebuiltGmsCoreVic_CronetDynamite \
    PrebuiltGmsCoreVic_DynamiteLoader \
    PrebuiltGmsCoreVic_DynamiteModulesA \
    PrebuiltGmsCoreVic_DynamiteModulesC \
    PrebuiltGmsCoreVic_GoogleCertificates \
    PrebuiltGmsCoreVic_MapsDynamite \
    PrebuiltGmsCoreVic_MeasurementDynamite \
    AndroidPlatformServices \
    MlkitBarcodeUIPrebuilt \
    TfliteDynamitePrebuilt \
    VisionBarcodePrebuilt

# GmsCore / GSF / Play config (default-permissions, sysconfig, preferred-apps).
# These carry only config files, no app APKs.
$(call inherit-product, vendor/gms/product/blobs/product_blobs.mk)
$(call inherit-product, vendor/gms/system/blobs/system_blobs.mk)
$(call inherit-product, vendor/gms/system_ext/blobs/system-ext_blobs.mk)
