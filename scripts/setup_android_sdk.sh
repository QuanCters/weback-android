#!/usr/bin/env bash
# scripts/setup_android_sdk.sh
# Android SDK Provisioning and Detection Script for WeBack Android (FCT-001)
set -euo pipefail

CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"

DEFAULT_SDK_ROOT="${HOME}/.android/sdk"
SDK_ROOT="${ANDROID_HOME:-${DEFAULT_SDK_ROOT}}"

REQUIRED_PACKAGES=(
    "platforms;android-35"
    "build-tools;35.0.0"
    "platform-tools"
)

check_sdk() {
    echo "Checking Android SDK installation at: ${SDK_ROOT}"
    local missing=0

    if [ ! -d "${SDK_ROOT}" ]; then
        echo "[-] SDK directory missing: ${SDK_ROOT}"
        missing=1
    else
        echo "[+] SDK directory found: ${SDK_ROOT}"
    fi

    local sdkmanager_bin="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "${sdkmanager_bin}" ]; then
        echo "[-] sdkmanager missing or not executable at: ${sdkmanager_bin}"
        missing=1
    else
        local version
        version=$("${sdkmanager_bin}" --version 2>/dev/null || echo "unknown")
        echo "[+] cmdline-tools latest installed (sdkmanager version: ${version})"
    fi

    if [ ! -d "${SDK_ROOT}/platforms/android-35" ]; then
        echo "[-] Platform android-35 missing at: ${SDK_ROOT}/platforms/android-35"
        missing=1
    else
        echo "[+] Platform android-35 verified."
    fi

    if [ ! -x "${SDK_ROOT}/build-tools/35.0.0/apksigner" ]; then
        echo "[-] Build-tools 35.0.0 (apksigner) missing at: ${SDK_ROOT}/build-tools/35.0.0"
        missing=1
    else
        echo "[+] Build-tools 35.0.0 verified."
    fi

    if [ ! -x "${SDK_ROOT}/platform-tools/adb" ]; then
        echo "[-] Platform-tools (adb) missing at: ${SDK_ROOT}/platform-tools"
        missing=1
    else
        echo "[+] Platform-tools verified."
    fi

    if [ ${missing} -ne 0 ]; then
        echo ""
        echo "Actionable installation instructions:"
        echo "Run './scripts/setup_android_sdk.sh' to provision the missing Android SDK components."
        return 1
    fi

    echo ""
    echo "[SUCCESS] Android SDK toolchain is fully provisioned and ready."
    echo "To export environment variables in your shell:"
    echo "  export ANDROID_HOME=\"${SDK_ROOT}\""
    echo "  export PATH=\"\${ANDROID_HOME}/cmdline-tools/latest/bin:\${ANDROID_HOME}/platform-tools:\${ANDROID_HOME}/build-tools/35.0.0:\${PATH}\""
    return 0
}

install_sdk() {
    echo "Provisioning Android SDK at: ${SDK_ROOT}"
    mkdir -p "${SDK_ROOT}/cmdline-tools"

    local temp_dir
    temp_dir=$(mktemp -d)
    trap 'rm -rf "${temp_dir}"' EXIT

    local zip_file="${temp_dir}/cmdline-tools.zip"
    if [ ! -x "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]; then
        echo "Downloading command-line tools from: ${CMDLINE_TOOLS_URL}"
        curl -sSL -o "${zip_file}" "${CMDLINE_TOOLS_URL}"

        echo "Verifying SHA-256 checksum..."
        local actual_sha
        actual_sha=$(sha256sum "${zip_file}" | awk '{print $1}')
        if [ "${actual_sha}" != "${CMDLINE_TOOLS_SHA256}" ]; then
            echo "ERROR: SHA-256 checksum mismatch for commandlinetools!"
            echo "Expected: ${CMDLINE_TOOLS_SHA256}"
            echo "Actual:   ${actual_sha}"
            exit 1
        fi
        echo "[+] Checksum verified."

        echo "Extracting commandline-tools..."
        mkdir -p "${temp_dir}/extracted"
        unzip -q -o "${zip_file}" -d "${temp_dir}/extracted"
        mkdir -p "${SDK_ROOT}/cmdline-tools/latest"
        rm -rf "${SDK_ROOT}/cmdline-tools/latest"/*
        mv "${temp_dir}/extracted/cmdline-tools"/* "${SDK_ROOT}/cmdline-tools/latest/"
        echo "[+] cmdline-tools installed to ${SDK_ROOT}/cmdline-tools/latest"
    else
        echo "[+] cmdline-tools already installed."
    fi

    local sdkmanager="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"

    echo "Accepting licenses and installing required packages: ${REQUIRED_PACKAGES[*]}..."
    yes | "${sdkmanager}" --sdk_root="${SDK_ROOT}" --licenses >/dev/null 2>&1 || true
    "${sdkmanager}" --sdk_root="${SDK_ROOT}" "${REQUIRED_PACKAGES[@]}"

    echo "Verifying final SDK state..."
    check_sdk
}

case "${1:-}" in
    --check)
        check_sdk
        ;;
    --help|-h)
        echo "Usage: $0 [--check | --install]"
        echo "  --check    Verify current Android SDK installation without making changes."
        echo "  --install  Download and provision Android SDK packages (default if no flag given)."
        ;;
    *)
        install_sdk
        ;;
esac
