# Visual runtime third-party notices

The pure Kotlin visual runtime has no third-party runtime dependency beyond the project’s existing
Kotlin toolchain.

The paired Android module uses:

- Google Filament 1.71.5 — Apache License 2.0.
- Vico 3.2.1 — Apache License 2.0.

Only local programmatic geometry and the application’s own semantic data are accepted at runtime.
Model-provided SVG, GLB, scripts, URLs, and remote assets are not supported by the protocol.
