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

# A17 (EvoX cnb): GmsCore is now a PREBUILT APEX, not a standalone app.
# vendor/gms/apex/Android.bp declares prebuilt_apex "com.google.android.gmssystem"
# (file com.google.android.gmssystem.prodvic.apex, 155MB) which CONTAINS
# PrebuiltGmsCoreVic as an app. On A16 (bka) PrebuiltGmsCoreVic was a standalone APK at
# vendor/gms/product/packages/privileged_apps/PrebuiltGmsCore/PrebuiltGmsCoreVic.apk.
# In cnb that APK is GONE and vendor/gms/apex/apps/ ships only a stub
# `android_app { name: "PrebuiltGmsCoreVic" }` with a ZERO-BYTE AndroidManifest.xml, so
# listing the bare app here makes manifest_fixer fail:
#     error: no element found: line 1, column 0
# EvoX's own gms_pico.mk:23 uses the apex name, so follow that.
DISABLE_DEXPREOPT_CHECK := true

PRODUCT_PACKAGES += \
    ConfigUpdater \
    Phonesky \
    GoogleServicesFramework \
    com.google.android.gmssystem.prodvic \
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
