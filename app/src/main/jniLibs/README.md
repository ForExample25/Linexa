# jniLibs/

Provenance, versions, dependency closure, and SHA-256 pins for every
vendored binary: see [BINARY-MANIFEST.md](BINARY-MANIFEST.md) and
`app/src/main/assets/bootstrap-manifest.json`.

The `lib__*__.so` naming and `useLegacyPackaging = true` requirements
(AGP auto-packages only `lib*.so` jniLibs entries) are documented in
BINARY-MANIFEST.md ("They must keep the `lib__*__.so` names") and in the
`packaging { jniLibs }` block of `app/build.gradle.kts`.
