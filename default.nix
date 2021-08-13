let
    pkgs = import <nixpkgs> {};
    jre = pkgs.jdk;
    sbt = pkgs.sbt.override { jre = jre; };
    buildInputs = [
        jre
        sbt
    ];
in {
    shell = pkgs.mkShell {
        buildInputs = buildInputs;
    };
}
