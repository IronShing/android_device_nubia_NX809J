#
# config.fs for Nubia Red Magic 11 Pro (NX809J / canoe / SM8750)
#
# SoC-level vendor user/group declarations cribbed verbatim from
# device/oneplus/sm8750-common/config.fs (LineageOS lineage-23.2)
# because both devices share the same Qualcomm SM8750 chipset and
# use the same vendor init scripts that reference these AIDs.
#
# Plus 2 Nubia-specific additions (vendor_modprobe,
# vendor_unsignedhexlpservice) referenced by Nubia's vendor init
# scripts but not present in dodge's config.fs. AIDs allocated at
# 2950+ to leave room for future SoC-level additions.

# === SoC-level (from dodge sm8750-common, AIDs 2901-2917) ===

[AID_VENDOR_QTI_DIAG]
value:2901

[AID_VENDOR_QDSS]
value:2902

[AID_VENDOR_RFS]
value:2903

[AID_VENDOR_RFS_SHARED]
value:2904

[AID_VENDOR_ADPL_ODL]
value:2905

[AID_VENDOR_QRTR]
value:2906

[AID_VENDOR_THERMAL]
value:2907

[AID_VENDOR_FASTRPC]
value:2908

[AID_VENDOR_QTR]
value:2909

[AID_VENDOR_NXP_STRONGBOX]
value:2910

[AID_VENDOR_NXP_WEAVER]
value:2911

[AID_VENDOR_SSGTZD]
value:2912

[AID_VENDOR_THALES_STRONGBOX]
value:2913

[AID_VENDOR_QCC]
value:2914

[AID_VENDOR_NXP_AUTHSECRET]
value:2915

[AID_VENDOR_THALES_WEAVER]
value:2916

[AID_VENDOR_THALES_AUTHSECRET]
value:2917

# === Nubia-specific (not in dodge) ===

[AID_VENDOR_MODPROBE]
value:2950

[AID_VENDOR_UNSIGNEDHEXLPSERVICE]
value:2951
