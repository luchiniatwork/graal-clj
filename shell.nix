{ pkgs ? import <nixpkgs> {} }:

let
  clj = pkgs.callPackage ./clojure-derivation {};
in
pkgs.mkShell {
  packages = [ clj ];

  buildInputs = [

    # keep this line if you use bash
    pkgs.bashInteractive
  ];
}
