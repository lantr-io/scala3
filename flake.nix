{
  description = "Dev shell for scala3-compiler-sjs (Scala 3 compiler on Scala.js/Wasm)";

  inputs = {
    # Same nixpkgs revision as ~/projects/lantr/scalus/flake.lock, to reuse its store paths.
    nixpkgs.url = "github:NixOS/nixpkgs/2bd3427b41d10b8318383195efe502ed1baca6cd";
    flake-utils.url = "github:numtide/flake-utils/11707dc2f618dd54ca8739b309ec4fc024de578b";
  };

  outputs = { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.openjdk25;
      in
      {
        devShells.default = pkgs.mkShell {
          JAVA_HOME = "${jdk}";
          packages = [
            jdk
            (pkgs.sbt.override { jre = jdk; })
            pkgs.nodejs_24
          ];
        };
      });
}
