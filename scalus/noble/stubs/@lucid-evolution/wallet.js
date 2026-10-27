// Stub: scalus-cardano-ledger's npm facade (Lucid.scala) imports this for HD wallets.
// The browser toolchain links that facade but playground programs never call it.
export function walletFromSeed() {
  throw new Error("@lucid-evolution/wallet is not available in the browser toolchain");
}
