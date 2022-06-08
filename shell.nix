{ pkgs ? import <nixpkgs> {} }:

let
  clj = pkgs.callPackage ./clojure-derivation {};
in
pkgs.mkShell {
  packages = [ clj ];

  buildInputs = [
    pkgs.hello

    #   pkgs.clojure
    #_(import ./clojure-derivation { inherit pkgs; })

    # keep this line if you use bash
    pkgs.bashInteractive
  ];
}

  #  with import <nixpkgs> {}; callPackage ./clojure.nix {}


  #    with import <nixpkgs> {};

  #    (callPackage ./clojure-derivation/default.nix { })
