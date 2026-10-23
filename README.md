# GhostHand

Android work in a sandbox with no Android SDK, no Gradle, no Maven and no root.

* **[RECIPE.md](RECIPE.md)** — how to get a working Java + Android XML toolchain out
  of PyPI, npm and GitHub alone (and why each step is needed), plus how to get
  **AndroidX** (appcompat, material, recyclerview, …) with Maven unreachable.
* **[toolchain/](toolchain/)** — the working implementation: fetch a JRE, ECJ, D8/R8,
  apksigner, aapt2, android.jar and apktool, then test-compile, unit-test and build a
  signed APK.
* **[sample/](sample/)** — a runnable framework-only app used to prove the pipeline.
* **[sample-androidx/](sample-androidx/)** — the same for AndroidX: AppCompatActivity,
  Material 3 theme, RecyclerView, ConstraintLayout, lifecycle ViewModel and unit tests.
  `build.sh` ends by checking the *artifact* (self-containment, manifest components,
  dex count against the declared floor, vector bases) — see RECIPE.md section 9.5 for
  the six defects that made those checks necessary.
* **[BT-AUDIO-LOCAL.md](BT-AUDIO-LOCAL.md)** — build/test BT-Audio's C#, WinForms,
  and PowerShell sender locally, without Actions. Includes an explicitly opt-in
  workaround for this sandbox's blocked .NET/NuGet download hosts and its limits.
* **[REVERSE-TOOLS.md](REVERSE-TOOLS.md)** — use JADX, Apktool, and CFR here, including
  a fresh-temp bootstrap/test script, the verified commands, and version/provenance
  limits.
* **[PYTHON-UI-TESTING.md](PYTHON-UI-TESTING.md)** — tested headless PySide6/Qt Widgets
  screenshots plus a JSON report of widget geometry, style, layout, and state; documents
  the CPU-raster-only sandbox workaround and its limits.
* **[AGENTS.md](AGENTS.md)** tells coding agents how to stay within the 128 MB artifact
  budget and clean up generated tools and outputs.

```bash
bash toolchain/setup.sh                 # ~11 s
bash toolchain/check.sh sample          # test-compile Java + XML
bash toolchain/test.sh  sample          # run JUnit tests on the JVM
bash toolchain/build.sh sample --verify # signed, zipaligned APK

bash toolchain/androidx.sh              # ~31 s: fetch AndroidX (no Maven involved)
bash toolchain/check.sh sample-androidx
bash toolchain/build.sh sample-androidx --release --verify

bash toolchain/reverse-tools.sh test     # fetch/test JADX, Apktool, CFR; auto-cleans tools
bash toolchain/python-ui-inspect.sh test  # render/inspect a PySide6 widget headlessly; auto-cleans
```
