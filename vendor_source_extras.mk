# Boot-critical vendor components that closure.py excludes from prebuilts because
# they are SOURCE-buildable but were never added to PRODUCT_PACKAGES.
# See [[vendor_img_build_native_enforcing]] gap-refresh 2026-07-06.
#
# vndservicemanager: clean AOSP source module (frameworks/native/cmds/servicemanager) —
#   legacy HIDL vendor-service registrar that stock ships. Source-build.
PRODUCT_PACKAGES += \
    vndservicemanager \
    vndservice
#
# NOTE: libaudiocorehal.{qti,default} / libpalipcservice / libagmipcservice were tried as
# SOURCE (hardware/qcom-caf/sm8750/audio) but the AGM aidlconverter fails to compile —
# missing GSL techpack header (gsl_intf.h not on its header_libs path; classic QTI-audio
# source cascade). Since these are stock QTI binaries, the "as-good-as-stock" path is to
# ship the stock PREBUILTS (remove them from closure.py EXCLUDE_PATHS) rather than fight
# the source header cascade. TODO next iteration.
