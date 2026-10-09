#!/usr/bin/env bash
# BT-Audio Windows-sender tests on THIS restricted Debian x86_64 sandbox.
# See ../BT-AUDIO-LOCAL.md before running. The normal, preferred route is
# BT-Audio/btaudio/windows/prepare-tools.sh (official SDK + PowerShell 7.4.6).
# This opt-in fallback uses pinned third-party Git copies of an older SDK and
# PowerShell; it DOES NOT claim to exercise the pinned Windows workflow stack.
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: bash toolchain/bt_audio_local.sh --unofficial-mirrors /path/to/BT-Audio [prepare|test]

  prepare  Bootstrap an external cache without running the test suites.
  test     Bootstrap, compile embedded C# and the GUI, parse/execute PowerShell,
           and run the sender's wire tests (default).

This is a fallback for this locked-down Debian x86_64 sandbox, NOT a recommended
production toolchain. It downloads untrusted Git-tracked binary mirrors of SDK
8.0.417 (BT-Audio pins 8.0.425), PowerShell 7.1.3 (BT-Audio pins 7.4.6),
and an old OpenSSL 1.1 library. The opt-in flag acknowledges those substitutions.
It uses a pty-only LD_PRELOAD shim with the old PowerShell for local wire tests.
No source or checked-in output in BT-Audio is changed; artifacts go to its ignored
build/ directories, and the large cache stays OUTSIDE the checkout.
EOF
}

die() { printf 'bt_audio_local: %s\n' "$*" >&2; exit 2; }
[ "${1:-}" = "--unofficial-mirrors" ] && [ $# -ge 2 ] && [ $# -le 3 ] || { usage >&2; exit 2; }
[ -d "$2" ] || die "no BT-Audio checkout at $2"
BT="$(cd "$2" && pwd -P)"
ACTION="${3:-test}"
case "$ACTION" in prepare|test) ;; *) usage >&2; exit 2 ;; esac
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
GH="$(cd "$HERE/.." && pwd -P)"
[ -f "$BT/btaudio/windows/cscheck.sh" ] && [ -f "$BT/pscheck/guicheck.sh" ] \
    && [ -f "$BT/btaudio/windows/pscheck.sh" ] || die "not a BT-Audio checkout with the local Windows checks"
grep -q 'SDK_VERSION="8.0.425"' "$BT/btaudio/windows/cscheck.sh" \
    && grep -q 'sdk/8.0.425/Roslyn/bincore/csc.dll' "$BT/pscheck/guicheck.sh" \
    || die "BT-Audio's pinned SDK path has changed; review this fallback before running it"
for bin in git python3 sha512sum sha256sum unzip dpkg-deb gcc realpath; do
    command -v "$bin" >/dev/null 2>&1 || die "missing $bin"
done
if [ "$ACTION" = test ]; then
    command -v socat >/dev/null 2>&1 || die "wire tests require socat"
fi

CACHE="${BTAUDIO_WINDOWS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/btaudio/windows}"
case "$CACHE" in /*) ;; *) CACHE="$PWD/$CACHE" ;; esac
CACHE="$(realpath -m "$CACHE")"
case "$CACHE/" in
    "$GH/"*|"$BT/"*) die "cache must be outside the checkout: $CACHE" ;;
esac
[ ! -L "$CACHE" ] || die "refusing symlinked cache root: $CACHE"
mkdir -p "$CACHE/mirrors" "$CACHE/dotnet-cli" "$CACHE/nuget"

printf '\nWARNING: unofficial Git copies of binaries; versions are NOT the BT-Audio CI pins.\n'
printf '         SDK 8.0.417, PowerShell 7.1.3, OpenSSL 1.1; pty-only shim.\n'
printf '         Do not deploy these tools or mistake these tests for Windows testing.\n\n'

# Fetch exactly one commit and one subtree, never a release-asset redirect.
# Re-runs verify the cached commit and hashes, and do not modify existing repos.
get_tree() {
    local name="$1" repo="$2" sha="$3" subtree="$4" dir="$CACHE/mirrors/$1"
    if [ -d "$dir/.git" ]; then
        [ "$(git -C "$dir" rev-parse HEAD)" = "$sha" ] \
            || die "existing $name mirror is not at expected commit $sha; inspect it"
    else
        [ ! -e "$dir" ] || die "refusing non-repository $dir"
        mkdir -p "$dir"
        git -C "$dir" init -q
        git -C "$dir" remote add origin "https://github.com/$repo.git"
        git -C "$dir" fetch -q --depth 1 --filter=blob:none origin "$sha"
        git -C "$dir" sparse-checkout set "$subtree"
        git -C "$dir" checkout -q --detach "$sha"
    fi
    [ -z "$(git -C "$dir" status --porcelain --untracked-files=all)" ] \
        || die "locally modified $name mirror at $dir; inspect it"
    printf '%s\n' "$dir"
}
check_hash() {
    local algorithm="$1" expected="$2" file="$3"
    printf '%s  %s\n' "$expected" "$file" | "${algorithm}sum" --check --status \
        || die "${algorithm} checksum mismatch: $file"
}
# Never replace an existing installation. These links are a compatibility
# adapter for BT-Audio's fixed cache layout; the 8.0.425 link is explicitly NOT
# an 8.0.425 SDK. `dotnet --version` will report the actual 8.0.417 version.
link_once() {
    local src="$1" dest="$2"
    if [ -L "$dest" ]; then
        [ "$(readlink -f "$dest")" = "$(readlink -f "$src")" ] \
            || die "refusing to replace $dest"
    elif [ -e "$dest" ]; then
        die "refusing to replace existing $dest"
    else
        ln -s "$src" "$dest"
    fi
}

SDK_REPO="$(get_tree sdk doublegate/WRAITH-Protocol f7aaabbb93b9c47c56fd7d1d0536e84f291059db .dotnet)"
SDK="$SDK_REPO/.dotnet"
check_hash sha512 5603f033a4bf6227b1b628b8d3f4936e73351388cbdac17528dfb233f9d52b3fbd5fa6175bf6c2ff75db53d100f8ef9d64cdcade73f97218feb04cbcbfa7edc8 "$SDK/dotnet"
python3 - "$SDK/dotnet" <<'PY' || die "mirrored dotnet is not a Linux x86_64 ELF"
import pathlib, sys
b = pathlib.Path(sys.argv[1]).read_bytes()[:20]
assert b[:4] == b'\x7fELF' and int.from_bytes(b[18:20], 'little') == 62
PY
[ "$(DOTNET_ROOT="$SDK" DOTNET_CLI_HOME="$CACHE/dotnet-cli" "$SDK/dotnet" --version)" = 8.0.417 ] \
    || die "mirrored SDK is not 8.0.417"
for src in "$SDK"/*; do
    [ "${src##*/}" = sdk ] && continue
    link_once "$src" "$CACHE/${src##*/}"
done
[ ! -L "$CACHE/sdk" ] || die "refusing symlinked $CACHE/sdk"
mkdir -p "$CACHE/sdk"
link_once "$SDK/sdk/8.0.417" "$CACHE/sdk/8.0.417"
link_once "$SDK/sdk/8.0.417" "$CACHE/sdk/8.0.425"

REF_REPO="$(get_tree refs Mai-xiyu/REPO-ChatPlus b3aa35cbdec0e64476bba2ddbb2436fb3b2c80cb nuget-packages/microsoft.netframework.referenceassemblies.net48/1.0.3)"
NUPKG="$REF_REPO/nuget-packages/microsoft.netframework.referenceassemblies.net48/1.0.3/microsoft.netframework.referenceassemblies.net48.1.0.3.nupkg"
check_hash sha512 5d62a0c9e35a74d71341a215bd007c06b74236b11aafa7e8fdd7b539d41167d1c5cb48dc05268cf08579abae196a6a6c4a70bc0254ec002686654ba8170e3544 "$NUPKG"
if [ ! -f "$CACHE/refs/build/.NETFramework/v4.8/mscorlib.dll" ]; then
    mkdir -p "$CACHE/refs"
    unzip -q -o "$NUPKG" -d "$CACHE/refs"
fi
REFS="$CACHE/refs/build/.NETFramework/v4.8"
check_hash sha256 6b35530467b914b0b195146ca1d1485ddd219afaad6461103f16f54e952f75a9 "$REFS/mscorlib.dll"
check_hash sha256 2fe343569f794f2ca92ee14a41875571a9f21bf92637b8f8ee86306534209cca "$REFS/System.dll"
check_hash sha256 94a1fc97eb0d36f13acd209bb7207da504c8be10caf7e5096b2c46e671dc6fe2 "$REFS/System.Core.dll"
check_hash sha256 65a42d3e5bd4508e3c75133cd1967301a84b5dae6698f300cd831ff79c54b611 "$REFS/System.Drawing.dll"
check_hash sha256 0b0bc1f2ebfefe0cf827b2e2a0caa3fb8c772d84df26ab8e40abe98e0f2eb300 "$REFS/System.Windows.Forms.dll"

PS_REPO="$(get_tree pwsh achuchulev/terraform-external-provider-ps c55ad39706c4a8ecccf5de9c609f3fb83c703103 ps-linux-x64)"
PSBIN="$PS_REPO/ps-linux-x64/pwsh"
check_hash sha512 f17b14ee8690a66c4884e5eee71be403e9373dc1bed67212ad83f8adfb89b7cac39c12785c9ddd3e001634e1a7282aa02d5b6541dabe49ad57e1ed2463c61f26 "$PSBIN"
[ -x "$PSBIN" ] || die "PowerShell Git blob is not executable: $PSBIN"
SSL_REPO="$(get_tree openssl assohail/solana-ico-token 996a6f9ad8335ba12bec184128b5fa0f6858bce0 libssl1.1_1.1.1-1ubuntu2.1~18.04.20_amd64.deb)"
DEB="$SSL_REPO/libssl1.1_1.1.1-1ubuntu2.1~18.04.20_amd64.deb"
check_hash sha256 bb8a940ba6fd293545c3172b7fea5a09354cc07e035a1388134a676845cdcf9f "$DEB"
[ "$(dpkg-deb -f "$DEB" Architecture)" = amd64 ] || die "unexpected libssl architecture"
if [ ! -f "$CACHE/openssl11/usr/lib/x86_64-linux-gnu/libcrypto.so.1.1" ]; then
    mkdir -p "$CACHE/openssl11"
    dpkg-deb -x "$DEB" "$CACHE/openssl11"
fi
SSL="$CACHE/openssl11/usr/lib/x86_64-linux-gnu"
check_hash sha256 e86845de2c437c3159b75644a8a8bd10182f5ccf4f12cf0f4b751b03d5dcb983 "$SSL/libssl.so.1.1"
check_hash sha256 181fa1903102e65575b2c4563019ec72aa5acf2ada38f8d3ba68f549728741c7 "$SSL/libcrypto.so.1.1"

gcc -O2 -shared -fPIC -Wall -Wextra -o "$CACHE/pty-modem-shim.so" \
    "$HERE/bt_audio_pty_shim.c" -ldl
[ ! -L "$CACHE/pwsh" ] || die "refusing symlinked $CACHE/pwsh"
mkdir -p "$CACHE/pwsh"
if [ -e "$CACHE/pwsh/pwsh" ] && ! grep -q 'GHOSTHAND BT-AUDIO FALLBACK' "$CACHE/pwsh/pwsh"; then
    die "refusing to replace existing $CACHE/pwsh/pwsh"
fi
cat > "$CACHE/pwsh/pwsh" <<'EOF'
#!/bin/sh
# GHOSTHAND BT-AUDIO FALLBACK - for the old pwsh's local pty tests ONLY.
cache=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P) || exit 1
export LD_LIBRARY_PATH="$cache/openssl11/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LD_PRELOAD="$cache/pty-modem-shim.so${LD_PRELOAD:+:$LD_PRELOAD}"
exec "$cache/mirrors/pwsh/ps-linux-x64/pwsh" "$@"
EOF
chmod +x "$CACHE/pwsh/pwsh"

export BTAUDIO_WINDOWS_CACHE="$CACHE"
export DOTNET_ROOT="$CACHE" DOTNET_CLI_HOME="$CACHE/dotnet-cli" NUGET_PACKAGES="$CACHE/nuget"
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
export PATH="$CACHE/pwsh:$CACHE:$PATH"
[ "$(pwsh -NoProfile -Command '$PSVersionTable.PSVersion.ToString()')" = 7.1.3 ] \
    || die "fallback pwsh did not report 7.1.3"

bash "$BT/btaudio/windows/prepare-tools.sh" ensure
printf '\nBT-Audio local toolchain READY: real SDK %s, pwsh %s.\n' \
    "$("$CACHE/dotnet" --version)" "$(pwsh -NoProfile -Command '$PSVersionTable.PSVersion.ToString()')"
printf 'Cache (external): %s\n' "$CACHE"
if [ "$ACTION" = prepare ]; then
    printf '\nTo run without the workflow from this sandbox:\n'
    printf '  BTAUDIO_WINDOWS_CACHE=%q bash %q --unofficial-mirrors %q test\n' "$CACHE" "$HERE/bt_audio_local.sh" "$BT"
    exit 0
fi
cd "$BT"
bash btaudio/windows/cscheck.sh
bash btaudio/windows/pscheck.sh
bash pscheck/guicheck.sh
printf '\nTests passed with the stated VERSION SUBSTITUTIONS and pty-only shim.\n'
printf 'Windows binary: %s (not installed and not copied to output/)\n' \
    "$BT/pscheck/build/gui-out/BTAudioSender.exe"
printf 'Windows PowerShell 5.1/WASAPI/Bluetooth were not run here.\n'
