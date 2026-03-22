{
  description = "bardigan-cay development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { 
        inherit system;
        config.allowUnfree = true;
      };
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          # Clojure ecosystem
          jdk25_headless
          clojure
          babashka

          # Node.js for shadow-cljs and npm dependencies
          nodejs_22

          # AI tooling
          claude-code
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
