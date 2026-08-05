{
  description = "bardigan-cay development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    nixpkgs-unstable.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs = { self, nixpkgs, nixpkgs-unstable }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };
      pkgs-unstable = import nixpkgs-unstable {
        inherit system;
        config.allowUnfree = true;
      };

      # Shared language toolchain (babashka + JDK + clojure + node), imported
      # by both leaves so the dev shell and the toolchain image build against
      # identical pinned versions. A plain Nix value — not a NixOS module.
      toolchain = import ./nix/toolchain.nix { inherit pkgs; };
    in {
      # LEAF 1 — interactive development (AI tooling, gh, skopeo).
      devShells.${system}.default =
        import ./nix/dev-shell.nix { inherit pkgs pkgs-unstable toolchain; };

      # LEAF 2 — the bardigan-cay-build toolchain image (pure toolchain + the
      # tools to rebuild and publish itself; no CI-system specifics).
      packages.${system}.bardigan-cay-build =
        import ./nix/build-image.nix { inherit pkgs toolchain; };
    };
}
