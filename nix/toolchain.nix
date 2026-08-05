# nix/toolchain.nix
#
# The shared language toolchain — the version-critical bits that MUST be
# identical between the interactive dev shell (nix/dev-shell.nix) and the
# bardigan-cay-build toolchain image (nix/build-image.nix).
#
# This is a plain Nix value imported by both leaves. It is NOT a NixOS module:
# there is no option schema and no evalModules — just a function returning an
# attrset, which is the right-sized tool for sharing a handful of derivations.
#
# Host-provided basics (coreutils, git, bash, curl) are intentionally NOT here:
# the dev shell inherits them from the host, while the from-scratch container
# enumerates them itself in nix/build-image.nix.
{ pkgs }:
rec {
  jdk = pkgs.jdk25_headless;

  # Node.js for shadow-cljs and npm dependencies (npm ships with the node
  # package).
  nodejs = pkgs.nodejs_22;

  # Language toolchain shared verbatim by dev + the build image: babashka
  # drives the build tasks, clojure/JDK compile the server uberjar, node runs
  # shadow-cljs for the client, lightningcss minifies CSS during asset
  # inlining (build/inline.clj). lightningcss comes from nixpkgs rather than
  # the npm lightningcss-cli package: npm ships a prebuilt glibc binary that
  # cannot execute in the from-scratch build image (no /lib64 loader).
  core = [ pkgs.babashka jdk pkgs.clojure nodejs pkgs.lightningcss ];
}
