#!/usr/bin/env bash
# Re-download the personal-build ReFra APK (gitignored, never published).
# usage: ./fetch.sh [version]   e.g. ./fetch.sh 5.1.5
set -euo pipefail
cd "$(dirname "$0")"
V="${1:-5.1.5}"
# per-ABI build number: 513004 for 5.1.3, 515014 for 5.1.5 — check the release page
# but check the release page if this 404s.
A="ReFra-${V}-${B:-515014}-offline-WithML-arm64-v8a-release.apk"
U="https://github.com/IacobIonut01/ReFra/releases/download/${V}/${A}"
echo "==> $U"
curl -fSL --progress-bar -o ReFra.apk.tmp "$U"
mv -f ReFra.apk.tmp ReFra.apk
echo "md5: $(md5sum ReFra.apk | cut -d' ' -f1)  size: $(stat -c%s ReFra.apk)"
echo "==> preinstall safety assertions (see README.md)"
unzip -v ReFra.apk | awk '$0 ~ /\.so$/ {print $3}' | sort -u
echo "(the line above must be 'Stored' only)"
