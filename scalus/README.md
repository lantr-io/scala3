# Scalus browser toolchain

This branch builds the Scala 3 compiler for the browser (Scala.js/WebAssembly) with the Scalus
compiler plugin and Scalus's macros linked in. [play.scalus.org](https://play.scalus.org) loads it.

| File | Role |
|---|---|
| `SCALUS_REF` | The Scalus git tag the toolchain is built against |
| `build-toolchain.sh` | Builds the whole toolchain from a Scalus checkout into a tarball |
| `sync-plugin.py` | Generates `compiler/src-scalus-plugin` from that checkout (not committed) |
| `prelink-macros.mjs` | Emits Scalus's macros as IR, linked into the compiler's second build |
| `assets.py` | Adds the Scalus jars and runtime IR to the browser assets |
| `noble/` | Bundles (and stubs) the npm modules linked programs import |
| `dev/` | Run the browser compiler under Node, for debugging |

## Releasing

- **New Scalus version:** run the "Bump Scalus" workflow with the Scalus tag (e.g. `v1.4.2`) and
  the next toolchain tag (e.g. `scalus-toolchain-v0.2.0`). It commits `SCALUS_REF`, tags, builds,
  releases, and publishes to `https://lantr.io/scalus-scala3-wasm/toolchain/<tag>/`.
- **Toolchain change, same Scalus:** push a `scalus-toolchain-v*` tag.

Then set the playground's `TOOLCHAIN_VERSION` to the new tag.

## Building locally

```bash
git clone -b "$(cat scalus/SCALUS_REF)" https://github.com/scalus3/scalus.git /tmp/scalus
scalus/build-toolchain.sh /tmp/scalus /tmp/scalus-toolchain.tar.gz
```
Needs JDK 25, Node 24 and sbt.
