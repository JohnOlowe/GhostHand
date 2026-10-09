# BT-Audio's .NET and PowerShell sender — build locally, without Actions

Audited [BT-Audio at `25e0c360`](https://github.com/JohnOlowe/BT-Audio/tree/25e0c360d9151b431e57c97915f327460738dbe7)
(`arena/10caee85-bt-audio`) on Debian 12 x86_64. **You do not need the
`.github/workflows/windows-sender.yml` workflow to compile or test it.** The
workflow calls shell scripts already in the repo. PowerShell `.ps1` is *parsed
and executed*, not compiled to an executable; its embedded `Add-Type` C# and
the WinForms `.exe` **are** compiled.

## If Microsoft and NuGet downloads work: the preferred path

In a separate BT-Audio checkout on Linux (with `bash`, `python3`, `curl`, `unzip`,
`git`, `socat`, PyPI and npm access):

```bash
git clone --branch arena/10caee85-bt-audio --single-branch \
  https://github.com/JohnOlowe/BT-Audio.git BT-Audio
cd BT-Audio
bash btaudio/windows/prepare-tools.sh ensure # cache outside the checkout
bash btaudio/windows/cscheck.sh        # Add-Type C# 5, net48 reference assemblies
bash btaudio/windows/pscheck.sh        # PowerShell parser + sender wire tests
bash pscheck/guicheck.sh              # WinForms exe + runnable Linux engine tests
# Optional: bash build-all.sh --windows   # also builds all Android projects
```

`prepare-tools.sh` reuses a system .NET **8.0.425** SDK or downloads and
SHA-512-checks its official tarball; obtains the **.NET Framework 4.8 reference
assemblies** from NuGet; installs **PowerShell 7.4.6** with `dotnet tool`; and,
where `java`/`javac` is missing, gets a JRE and Eclipse ECJ from PyPI/npm. It
uses `${BTAUDIO_WINDOWS_CACHE:-$HOME/.cache/btaudio/windows}`, **not the Git
checkout**. `socat` and Python must already be installed. The scripts write
build outputs under ignored `build/` directories, not the checked-in `output/`.

- `cscheck.sh`: extracts C# from `bt-audio-send.ps1`, runs Roslyn with
  `/langversion:5 /nostdlib` against the real net48 reference assemblies. This
  targets the C# language/API combination used by Windows PowerShell 5.1.
- `pscheck.sh`: also parses the `.ps1` with PowerShell's AST parser, runs two
  negative tests plus seven WAV-file and four loopback/stub cases, and checks
  the bytes using the Android app's actual `Proto`/`Adpcm` Java decoder.
- `guicheck.sh`: compiles `btaudio/windows/gui/{Core,Engine,Windows,MainForm,Program}.cs`
  into `pscheck/build/gui-out/BTAudioSender.exe` for **net48 / C# 7.3 / WinForms**;
  compiles the same `Core` and `Engine` against net8 Linux, runs four wire tests
  and an auto-quality ladder test.

This **compiles** Windows-only code; it cannot display the WinForms window,
exercise Windows WASAPI, connect Bluetooth, or execute Windows PowerShell 5.1
on Linux. A passing PowerShell 7 parser is not proof of every 5.1 behaviour.

## In this locked-down sandbox: experimental local fallback

Probed here: `builds.dotnet.microsoft.com` and `api.nuget.org` return connection
failures, and GitHub *release assets* are also inaccessible. GitHub **Git**,
PyPI and npm do work. So the preferred bootstrap above cannot start from an
empty cache here. This repo has an **opt-in** fallback that downloads Git-tracked
copies of the required files into an *external* cache, then invokes BT-Audio's
**unmodified** local checks. It does not use GitHub Actions or ask for root.

> **Security and fidelity warning:** the mirrored binaries below are third-party
> copies, **not authenticated official downloads**. The pinned commit IDs and
> hashes detect changes, not establish Microsoft provenance. The fallback uses
> **SDK 8.0.417 rather than 8.0.425**, **PowerShell 7.1.3 rather than 7.4.6**,
> and obsolete OpenSSL 1.1. Do not use them for production or claim the
> workflow's exact pinned stack was verified. Use the preferred path when the
> official endpoints are reachable. The flag deliberately makes this explicit.

From **this GhostHand checkout** (`/home/user/GhostHand`), with a separate
BT-Audio checkout as above, `gcc`, `dpkg-deb`, `socat`, and enough free space
(the external cache used ~1.4 GB in this run):

```bash
# Run from the GhostHand repository root. BT-Audio can be elsewhere.
bash toolchain/bt_audio_local.sh --unofficial-mirrors /path/to/BT-Audio prepare
bash toolchain/bt_audio_local.sh --unofficial-mirrors /path/to/BT-Audio test
```

`prepare` takes a cold external cache to usable JRE/ECJ + .NET + pwsh. `test`
revalidates it and runs the three original BT-Audio check scripts. To relocate
the cache, set **`BTAUDIO_WINDOWS_CACHE=/tmp/btaudio/windows`** for both calls;
keep it outside *both* checkouts. Repeating the calls reuses and checks the
cache. `bash /path/to/BT-Audio/btaudio/windows/prepare-tools.sh clean` deletes
it if its path ends in `/btaudio/windows` and clears the generated harness
files; it leaves tracked source and checked-in binaries alone. The helper
refuses to replace any pre-existing SDK/PowerShell installation in that cache.

What the fallback pins and why:

| File(s) | Git source (commit) | Actual version / validation |
|---|---|---|
| Linux x86_64 SDK directory | [`doublegate/WRAITH-Protocol` `f7aaabbb`](https://github.com/doublegate/WRAITH-Protocol/tree/f7aaabbb93b9c47c56fd7d1d0536e84f291059db/.dotnet) | `dotnet --version` = **8.0.417**, ELF x86_64 and pinned executable SHA-512 |
| net48 reference `.nupkg` | [`Mai-xiyu/REPO-ChatPlus` `b3aa35cb`](https://github.com/Mai-xiyu/REPO-ChatPlus/tree/b3aa35cbdec0e64476bba2ddbb2436fb3b2c80cb/nuget-packages/microsoft.netframework.referenceassemblies.net48/1.0.3) | **1.0.3**, pinned SHA-512; contains `mscorlib`, WinForms, Drawing, etc. |
| Linux `pwsh` + its dependencies | [`achuchulev/terraform-external-provider-ps` `c55ad397`](https://github.com/achuchulev/terraform-external-provider-ps/tree/c55ad39706c4a8ecccf5de9c609f3fb83c703103/ps-linux-x64) | `pwsh` = **7.1.3**, pinned executable SHA-512 |
| `libssl.so.1.1`, `libcrypto.so.1.1` | [`assohail/solana-ico-token` `996a6f9a`](https://github.com/assohail/solana-ico-token/tree/996a6f9ad8335ba12bec184128b5fa0f6858bce0) | Ubuntu 18.04 `libssl1.1` `.deb`, pinned SHA-256; extracted without `apt` or root |

`cscheck.sh` and `guicheck.sh` hard-code `sdk/8.0.425/Roslyn/bincore/csc.dll`.
The helper makes a **clearly disclosed alias** from that path to the cached
8.0.417 compiler; `dotnet --version` still reports **8.0.417**. It neither
edits the BT-Audio scripts nor passes that compiler off as 8.0.425.

PowerShell 7.1 needs OpenSSL 1.1 on Debian 12 to execute this script (otherwise
it aborts with `No usable version of libssl was found`). Its serial-port
library also treats a `socat` pseudo-terminal's unsupported modem-control
`ioctl`s as a fatal `ENOTTY`. The helper compiles
[`toolchain/bt_audio_pty_shim.c`](toolchain/bt_audio_pty_shim.c) and preloads it
**only into this fallback `pwsh` process**: only failed modem-pin calls on
`/dev/pts/` become no-ops with DTR/RTS off; normal I/O and real serial ports
remain untouched. No shim is used for `dotnet`, the decoder, or the GUI tests.
This makes a useful *local wire-path check*, **not** a Windows COM-port test.

### Actually observed on an unchanged BT-Audio checkout

At `25e0c360` (Git working tree still clean), **no workflow**, with the
explicit fallback above:

```text
cold prepare: 22 s; actual SDK 8.0.417, pwsh 7.1.3
CS COMPILE OK (net48 refs, C# 5)
parsed clean: 1777 AST nodes, 2226 tokens
PSCHECK OK      (2 negative + 7 File + 4 Loopback groups; 55 s)
BTAudioSender.exe built (49152 bytes; net48 WinForms, under pscheck/build/gui-out/)
harness.dll built (net8 Linux engine from the same sources)
GUICHECK OK     (4 audio wire paths + quality ladder; 104 s)
full helper test: exit 0, 2 m 40 s; no tracked changes in BT-Audio
```

Without OpenSSL 1.1 the old pwsh **aborted** during execution even though its
parser passed. With OpenSSL but without the pty shim, `SerialPort.Open()` failed
`Inappropriate ioctl for device`; that is why neither a parser-only check nor
an unqualified claim of a passing sender test would have been honest. The
checked-in `output/BTAudioSender.exe` was **not overwritten**; the new binary
is a compilation result, not a tested Windows installer or a byte-identical
rebuild of that checked-in executable.
