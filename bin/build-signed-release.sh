#!/usr/bin/env bash
# Builds and signs the KeepADB release APK exactly the way .github/workflows/release.yml
# does it, so a local run and the CI run produce byte-for-byte comparable results.
#
# Mirrors, step for step:
#   - JDK 21 (release.yml: actions/setup-java with java-version 21)
#   - Android build-tools 34.0.0 (release.yml: sdkmanager "build-tools;34.0.0")
#   - "$VERSION_ROOT/gradlew" testDebugUnitTest lintDebug assembleRelease (release.yml: "Build unsigned release APK")
#   - apksigner sign --v1-signing-enabled false --v2-signing-enabled true
#     --v3-signing-enabled true --v4-signing-enabled false (release.yml: "Sign and verify release APK")
#   - apksigner verify --verbose --print-certs
#   - sha256sum > *.apk.sha256
#
# Fail-closed keystore verification (not present in CI, which trusts the GitHub Secret):
#   - keystore SHA-256 checked against the resolved signing document BEFORE it is used to sign
#   - resulting APK's certificate SHA-256 checked against the resolved signing document AFTER signing
# A mismatch at either point aborts before anything is written outside the temp workspace.
#
# Secrets are never printed: passwords stay in shell variables scoped to this script's
# subshell, and the recovered keystore + vault notes are shredded in a trap on every exit path.
set -euo pipefail

VERSION_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
METADATA_ROOT="$(cd "$VERSION_ROOT/../.." && pwd)"
cd "$VERSION_ROOT"

if [[ -n "${KEEPADB_SIGNING_DOC:-}" ]]; then
  [[ "$KEEPADB_SIGNING_DOC" = /* ]] || {
    echo "FATAL: KEEPADB_SIGNING_DOC must be an absolute path." >&2
    exit 1
  }
  DOC="$KEEPADB_SIGNING_DOC"
else
  DOC="$METADATA_ROOT/docs/release-signing.md"
fi
if [[ ! -f "$DOC" ]]; then
  echo "FATAL: signing document not found at $DOC. Set KEEPADB_SIGNING_DOC to an absolute path in a public clone." >&2
  exit 1
fi
BUILD_TOOLS="$HOME/Android/Sdk/build-tools/34.0.0"
JAVA_HOME_RELEASE="/usr/lib/jvm/java-21-openjdk"
VAULT_ENTRY="android/keepadb-signing"

if [[ ! -x "$BUILD_TOOLS/apksigner" ]]; then
  echo "FATAL: $BUILD_TOOLS/apksigner not found. Install build-tools;34.0.0 via sdkmanager." >&2
  exit 1
fi
if [[ ! -d "$JAVA_HOME_RELEASE" ]]; then
  echo "FATAL: $JAVA_HOME_RELEASE not found. Release build requires JDK 21 (see $DOC)." >&2
  exit 1
fi

VERSION_NAME=$(grep -oE "versionName\s+'[^']+'" "$VERSION_ROOT/app/build.gradle" | grep -oE "'[^']+'" | tr -d "'")
VERSION_CODE=$(grep -oE 'versionCode\s+[0-9]+' "$VERSION_ROOT/app/build.gradle" | grep -oE '[0-9]+')
if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "FATAL: could not read versionName/versionCode from $VERSION_ROOT/app/build.gradle." >&2
  exit 1
fi

EXPECTED_KEYSTORE_SHA256=$(grep -A2 '^- Keystore SHA-256:' "$DOC" | grep -oE '[0-9a-f]{64}' | head -1)
EXPECTED_CERT_SHA256_COLONED=$(grep -A2 '^- Zertifikat SHA-256:' "$DOC" | grep -oE '([0-9A-F]{2}:){31}[0-9A-F]{2}')
EXPECTED_CERT_SHA256=$(echo "$EXPECTED_CERT_SHA256_COLONED" | tr -d ':' | tr '[:upper:]' '[:lower:]')
if [[ -z "$EXPECTED_KEYSTORE_SHA256" || -z "$EXPECTED_CERT_SHA256" ]]; then
  echo "FATAL: could not read expected fingerprints from $DOC. Has its format changed?" >&2
  exit 1
fi

WORKDIR=$(mktemp -d)
chmod 700 "$WORKDIR"
KEYSTORE="$WORKDIR/keepadb-release.p12"
NOTES="$WORKDIR/vault-notes.txt"

cleanup() {
  shred -u "$KEYSTORE" "$NOTES" 2>/dev/null || rm -f "$KEYSTORE" "$NOTES"
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

echo "==> [1/6] Restoring signing keystore from Vaultwarden ($VAULT_ENTRY)..."
export BW_SESSION="${BW_SESSION:-$(~/agent/bin/vault-session)}"
~/agent/bin/vault-get "$VAULT_ENTRY" notes > "$NOTES"
chmod 600 "$NOTES"

STORE_PASSWORD=$(grep '^store_password=' "$NOTES" | head -1 | cut -d= -f2-)
KEY_PASSWORD=$(grep '^key_password=' "$NOTES" | head -1 | cut -d= -f2-)
KEY_ALIAS=$(grep '^alias=' "$NOTES" | head -1 | cut -d= -f2-)
grep '^keystore_base64=' "$NOTES" | cut -d= -f2- | base64 -d > "$KEYSTORE"
chmod 600 "$KEYSTORE"

echo "==> [2/6] Verifying keystore fingerprint (fail-closed)..."
ACTUAL_KEYSTORE_SHA256=$(sha256sum "$KEYSTORE" | cut -d' ' -f1)
if [[ "$ACTUAL_KEYSTORE_SHA256" != "$EXPECTED_KEYSTORE_SHA256" ]]; then
  echo "FATAL: keystore SHA-256 mismatch." >&2
  echo "  expected (resolved signing document): $EXPECTED_KEYSTORE_SHA256" >&2
  echo "  actual (Vaultwarden recovery):      $ACTUAL_KEYSTORE_SHA256" >&2
  echo "  Refusing to sign with an unverified keystore. Do not proceed without resolving this." >&2
  exit 1
fi
echo "    OK: keystore matches documented fingerprint."

echo "==> [3/6] Building unsigned release APK (JDK 21, matches release.yml)..."
JAVA_HOME="$JAVA_HOME_RELEASE" "$VERSION_ROOT/gradlew" testDebugUnitTest lintDebug assembleRelease

UNSIGNED_APK=$(find "$VERSION_ROOT/app/build/outputs/apk/release" -maxdepth 1 -name "*-unsigned.apk" | head -1)
if [[ -z "$UNSIGNED_APK" ]]; then
  echo "FATAL: no unsigned release APK found under $VERSION_ROOT/app/build/outputs/apk/release/." >&2
  exit 1
fi

OUT_DIR="$VERSION_ROOT/app/build/outputs/apk/release"
FINAL_APK="$OUT_DIR/KeepADB-v${VERSION_NAME}.apk"

echo "==> [4/6] Signing with apksigner (v2+v3 only, matches release.yml)..."
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-type PKCS12 \
  --ks-pass "pass:$STORE_PASSWORD" \
  --ks-key-alias "$KEY_ALIAS" \
  --key-pass "pass:$KEY_PASSWORD" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$FINAL_APK" \
  "$UNSIGNED_APK"
unset STORE_PASSWORD KEY_PASSWORD

echo "==> [5/6] Verifying signature and certificate fingerprint (fail-closed)..."
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$FINAL_APK" \
  | grep -v "^WARNING: A restricted\|^WARNING: java.lang\|^WARNING: Use --enable\|^WARNING: Restricted methods"

ACTUAL_CERT_SHA256=$("$BUILD_TOOLS/apksigner" verify --print-certs "$FINAL_APK" 2>/dev/null \
  | grep "SHA-256 digest" | head -1 | grep -oE '[0-9a-f]{64}')
if [[ "$ACTUAL_CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "FATAL: signed APK's certificate SHA-256 does not match the resolved signing document." >&2
  echo "  expected: $EXPECTED_CERT_SHA256" >&2
  echo "  actual:   $ACTUAL_CERT_SHA256" >&2
  rm -f "$FINAL_APK"
  exit 1
fi
echo "    OK: signed APK certificate matches documented upstream identity."

echo "==> [6/6] Writing checksum..."
sha256sum "$FINAL_APK" | sed "s|$OUT_DIR/||" > "$FINAL_APK.sha256"

echo ""
echo "==> Done."
echo "    versionName=$VERSION_NAME versionCode=$VERSION_CODE"
echo "    APK:      $FINAL_APK"
echo "    Checksum: $FINAL_APK.sha256"
