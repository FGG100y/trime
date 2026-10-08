#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fetches the prebuilt sherpa-onnx Android library and extracts it into the
# layout the app expects:
#
#   app/libs/sherpa-onnx.jar                    (the Kotlin/Java API)
#   app/src/main/jniLibs/<abi>/*.so             (the native libraries)
#
# Only arm64-v8a is fetched by default, since that is the ABI the local voice
# input feature targets. Pass one or more ABIs as arguments to override, e.g.
#
#   script/fetch-sherpa-onnx.sh arm64-v8a x86_64
#
# This is intentionally a manual step: the prebuilt binaries are large and are
# not committed to the repository. See doc/voice-input.md for the rationale.

set -euo pipefail

SHERPA_VERSION="${SHERPA_VERSION:-1.13.8}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
libs_dir="${repo_root}/app/libs"
jni_root="${repo_root}/app/src/main/jniLibs"

abis=("$@")
if [ ${#abis[@]} -eq 0 ]; then
    abis=("arm64-v8a")
fi

aar_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

echo "Downloading sherpa-onnx ${SHERPA_VERSION} ..."
curl -fL --retry 3 -o "${tmp_dir}/sherpa-onnx.aar" "${aar_url}"

mkdir -p "${libs_dir}"
echo "Extracting classes.jar -> app/libs/sherpa-onnx.jar"
unzip -o -j "${tmp_dir}/sherpa-onnx.aar" classes.jar -d "${tmp_dir}" >/dev/null
cp "${tmp_dir}/classes.jar" "${libs_dir}/sherpa-onnx.jar"

for abi in "${abis[@]}"; do
    dest="${jni_root}/${abi}"
    mkdir -p "${dest}"
    echo "Extracting jni/${abi}/*.so -> app/src/main/jniLibs/${abi}/"
    unzip -o -j "${tmp_dir}/sherpa-onnx.aar" "jni/${abi}/*" -d "${dest}" >/dev/null
done

echo "Done. sherpa-onnx ${SHERPA_VERSION} is ready."
