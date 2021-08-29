let
    sources = import ./nix/sources.nix;
    pkgs = import sources.nixpkgs {
      overlays = [
        (_: _: { inherit sources; })
      ];
    };
    jre = pkgs.openjdk11;
    sbtJre = pkgs.sbt.override { jre = jre; };
in 
  with pkgs;
    pkgs.mkShell {
      buildInputs = [
        kustomize
        sbtJre
        scala
        scalafmt
      ];
    }

