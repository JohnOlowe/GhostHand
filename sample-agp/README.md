# sample-agp/ — the manifest a real AGP project has

A tiny project in the shape Gradle produces, to keep the AGP path honest:

* the manifest has **no** `package="..."` — since AGP 7 the application id lives in
  `app/build.gradle.kts` (`namespace = "..."`), and `aapt2 link` refuses to link a
  manifest without one;
* so `check.sh` / `test.sh` / `build.sh` read the namespace from the build file,
  patch a **copy** of the manifest under `build/`, and link that — the project's own
  files are never written to (an adapter that patches the real manifest breaks the
  Gradle build the check exists to mirror).

```bash
bash toolchain/check.sh sample-agp/app
bash toolchain/test.sh  sample-agp/app --source 17
bash toolchain/build.sh sample-agp/app --verify
```

An explicit `--package NAME` overrides whatever the manifest or the build file says.
