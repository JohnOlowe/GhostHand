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

```bash
bash toolchain/setup.sh                 # ~10 s
bash toolchain/check.sh sample          # test-compile Java + XML
bash toolchain/test.sh  sample          # run JUnit tests on the JVM
bash toolchain/build.sh sample --verify # signed, zipaligned APK

bash toolchain/androidx.sh              # ~35 s: fetch AndroidX (no Maven involved)
bash toolchain/check.sh sample-androidx
bash toolchain/build.sh sample-androidx --release --verify
```
