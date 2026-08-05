# nix/dev-shell.nix
#
# LEAF: the interactive development shell (devShells.default).
#
# The shared language toolchain plus developer-only extras — AI tooling,
# github-cli, and skopeo for the genesis image push. None of these land in the
# toolchain image (nix/build-image.nix); that is the whole point of the split.
{ pkgs, pkgs-unstable, toolchain }:
pkgs.mkShell {
  packages = toolchain.core ++ (with pkgs; [
    # AI tooling
    pkgs-unstable.claude-code

    # OCI image publishing (`bb image-publish` — the off-CI genesis push)
    skopeo

    # github cli
    github-cli
  ]);

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
}
