#!/usr/bin/env bash
set -euo pipefail
: "${RUNNER_TEMP:?}" "${RELEASE_KEYSTORE_BASE64:?Missing signing secret}" "${RELEASE_STORE_PASSWORD:?}" "${RELEASE_KEY_ALIAS:?}" "${RELEASE_KEY_PASSWORD:?}"
umask 077
mkdir -p "$RUNNER_TEMP/display-launcher-signing"
printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/display-launcher-signing/release.jks"
printf 'RELEASE_KEYSTORE_FILE=%s\n' "$RUNNER_TEMP/display-launcher-signing/release.jks" >> "$GITHUB_ENV"
