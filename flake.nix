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
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          # Clojure ecosystem
          babashka
          jdk25_headless    # verify with: nix search nixpkgs jdk25
          clojure

          # Node.js for shadow-cljs and npm dependencies
          nodejs_22

          # AI tooling
          pkgs-unstable.claude-code
        ];

        shellHook = ''
          echo "bardigan-cay dev environment loaded"
          echo "  Java:    $(java --version 2>&1 | head -1)"
          echo "  Clojure: $(clojure --version)"
          echo "  Node:    $(node --version)"

          if [ ! -d "node_modules" ]; then
            echo ""
            echo "Note: node_modules not found. Run 'npm install' to install dependencies."
          fi
        '';
      };
    };
}
