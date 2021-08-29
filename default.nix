let
    pkgs = import <nixpkgs> {};
    jre = pkgs.jdk;
    kustomize = pkgs.kustomize;
    scalafmt = pkgs.scalafmt;
    sbt = pkgs.sbt.override { jre = jre; };
    buildInputs = [
        jre
        sbt
        scalafmt
        kustomize
    ];
in {
    shell = pkgs.mkShell {
        buildInputs = buildInputs;
    };
}
