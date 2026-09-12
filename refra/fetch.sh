#!/usr/bin/env bash
# Re-download the personal-build ReFra APK (gitignored, never published).
# usage: ./fetch.sh [version]   e.g. ./fetch.sh 5.1.3
set -euo pipefail
cd "$(dirname "$0")"
V="${1:-5.1.3}"
# 513004 = the arm64-v8a slot of the per-ABI split numbering; it has been stable across 5.1.x
# but check the release page if this 404s.
A="ReFra-${V}-513004-offline-WithML-arm64-v8a-release.apk"
U="https://github.com/IacobIonut01/ReFra/releases/download/${V}/${A}"
echo "==> $U"
curl -fSL --progress-bar -o ReFra.apk.tmp "$U"
mv -f ReFra.apk.tmp ReFra.apk
echo "md5: $(md5sum ReFra.apk | cut -d' ' -f1)  size: $(stat -c%s ReFra.apk)"
echo "==> preinstall safety assertions (see README.md)"
unzip -v ReFra.apk | awk '$0 ~ /\.so$/ {print $3}' | sort -u
echo "(the line above must be 'Stored' only)"
