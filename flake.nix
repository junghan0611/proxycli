{
  description = "proxycli — CLI-to-OpenAI API proxy (Clojure/GraalVM native)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        graalvm = pkgs.graalvmPackages.graalvm-ce;

        fhsEnv = pkgs.buildFHSEnv {
          name = "proxycli-build";
          targetPkgs = pkgs: with pkgs; [
            clojure
            graalvm
            zlib
            glibc
            glibc.static
          ];
          runScript = pkgs.writeShellScript "proxycli-build-init" ''
            export JAVA_HOME=${graalvm}
            export GRAALVM_HOME=${graalvm}
            exec bash "$@"
          '';
        };
      in
      {
        packages.fhs = fhsEnv;

        devShells = {
          default = pkgs.mkShell {
            name = "proxycli";
            buildInputs = with pkgs; [ clojure graalvm ];
            JAVA_HOME = graalvm;
            GRAALVM_HOME = graalvm;
            shellHook = ''
              echo "proxycli dev shell (GraalVM $(native-image --version 2>/dev/null | head -1))"
              echo "  ./run.sh start       — Start server (JVM)"
              echo "  ./run.sh build       — Native binary build"
              echo "  ./run.sh test        — Run tests"
            '';
          };

          jvm = pkgs.mkShell {
            name = "proxycli-jvm";
            buildInputs = with pkgs; [ clojure jdk17_headless ];
            shellHook = ''
              echo "proxycli dev shell (JVM only)"
            '';
          };
        };
      });
}
