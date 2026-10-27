// Stub: scalus-cardano-ledger's npm facade (Lucid.scala) imports this Node-only library.
// The browser toolchain links that facade but playground programs never call it.
const unavailable = new Proxy({}, {
  get() {
    throw new Error("cardano-multiplatform-lib is not available in the browser toolchain");
  },
});
export const PrivateKey = unavailable;
