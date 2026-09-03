# microG variant: de-Googled base + microG's reimplementation of Play Services.
#
# Used by evolution_NX809J.mk when NX809J_MICROG := true. Deliberately does NOT inherit
# gms_core.mk -- microG REPLACES GmsCore/GSF/Play Store rather than sitting alongside them.
# com.android.vending here is microG Companion, which shares the Play Store package name, so the
# two can never coexist.
#
# The signature spoofing microG needs is already in this tree: ComputerEngine.isMicrogSigned()
# allowlists com.google.android.gms and com.android.vending, and generateFakeSignature() lets a
# microG-signed app present Google's certificate through its own "fake-signature" meta-data.
# No framework patch required, and nothing else on the device can abuse it.
#
# NOT provided by microG, so do not advertise it: Google Wallet tap-to-pay (needs hardware-backed
# attestation, which this device cannot supply anyway -- the TEE attests bootloader=unlocked).
# Android Auto is NOT a microG feature either, but it is not excluded by microG: Google's own AA
# app runs against microG when it is preinstalled as a priv-app with the projection role
# (android_auto.mk, 2026-09-02 -- earlier note here claiming "never reimplemented / impossible"
# was wrong; see sn-00-x/aa4mg). Unverified on this device until someone plugs into a car.

# Privileged apps are normally required to carry UNCOMPRESSED dex so the runtime can mmap them
# straight out of the APK. microG ships its APKs with compressed dex, and letting the build
# uncompress them rewrites the zip and destroys the APK Signature Scheme v2 block -- Android then
# rejects the APK as tampered and microG never installs. Verified both ways on 2026-08-31.
#
# This flag tells the build to leave privileged APKs alone. It is set HERE, in the microG product
# only, so the MinimalGApps and FullGApps builds keep the normal uncompressed-dex behaviour.
# Cost: microG's dex stays compressed, so it loads a little slower and uses a little more RAM.
# The alternative -- re-signing microG with the platform key -- would hand a third-party app every
# signature-level permission on the device, which is a far worse trade.
DONT_UNCOMPRESS_PRIV_APPS_DEXS := true

PRODUCT_PACKAGES += \
    MicroGGmsCore \
    MicroGCompanion \
    MicroGGsfProxy

# microG is useless if it gets dozed: GmsCore holds the push (FCM) connection.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.nx809j.microg=true
