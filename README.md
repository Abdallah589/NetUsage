# NetUsage

Per-app mobile (daily) and Wi-Fi (billing-cycle) data usage monitor. Built by GitHub Actions: see `.github/workflows/build.yml`.

Main files: `Data.kt` (counters, database, history rebuild, backup), `Sampler.kt` (recording service),
`MainActivity.kt` (bridge to the page), `assets/index.html` (UI and chart).
App icon: `res/drawable/ic_launcher_foreground.xml` and `res/values/ic_launcher_background.xml`.
