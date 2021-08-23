let
    pkgs = import <nixpkgs> {};
    jre = pkgs.jdk;
    kustomize = pkgs.kustomize;
    sbt = pkgs.sbt.override { jre = jre; };
    buildInputs = [
        jre
        sbt
        kustomize
    ];
in {
    shell = pkgs.mkShell {
        buildInputs = buildInputs;
    };
}
