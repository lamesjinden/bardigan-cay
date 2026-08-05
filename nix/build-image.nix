# nix/build-image.nix
#
# LEAF: the `bardigan-cay-build` toolchain image (packages.bardigan-cay-build).
#
# A Nix-derived OCI image containing exactly two things:
#   1. the toolchain needed to build and test bardigan-cay (`bb all` — shadow-cljs
#      via node/npm, the server uberjar via clojure/JDK), and
#   2. the tooling needed to rebuild and publish this image itself
#      (nix + skopeo — both daemonless).
#
# Nothing else. In particular, nothing CI-system-specific is baked in: any
# runner — a CI pipeline, or a developer with `docker run` — can use it
# unchanged. Build/load/publish it via the bb tasks `image-build`,
# `image-load`, and `image-publish` (see build/bb/image.clj), or directly via
# `nix build .#bardigan-cay-build`.
#
# The image is uid-agnostic: runners commonly execute containers as an
# arbitrary, passwd-less uid (e.g. the host user's uid, to keep bind-mounted
# workspace ownership sane), so $HOME is world-writable (mode 1777, like /tmp)
# and no tool here assumes a passwd entry or root. The exception is nix, which
# requires a real uid and a root-writable store — run image (re)builds as root.
{ pkgs, toolchain }:
let
  inherit (toolchain) jdk;

  # Basics the from-scratch container must carry itself (the dev shell inherits
  # these from the host, so they live here rather than in the shared toolchain).
  containerBase = with pkgs; [
    # shell + coreutils (runners drive builds through `sh -c`)
    bashInteractive
    coreutils
    findutils
    gnugrep
    gnused
    gawk
    gnutar
    gzip
    which
    # SCM + networking (git checkout; TLS to registry.npmjs.org and Maven Central)
    git
    openssh
    curl
    cacert
    # self-bootstrap: build the next image with nix, publish it with skopeo
    nix
    skopeo
  ];

  # Everything on the image's PATH: shared language toolchain + container basics.
  imageToolchain = toolchain.core ++ containerBase;
in
pkgs.dockerTools.streamLayeredImage {
  name = "bardigan-cay-build";
  tag = "latest";
  # Register the image's store paths in a Nix database. Without this, nix
  # running INSIDE the container treats the entire baked /nix/store as
  # unregistered garbage and deletes paths out from under itself while
  # substituting them (including libraries its own binary is using) — the
  # self-bootstrap `nix build` crashes mid-run. With the DB present, the
  # runtime closure is already valid and only build-time deps get fetched.
  includeNixDB = true;
  contents = imageToolchain
    ++ (with pkgs.dockerTools; [ binSh usrBinEnv fakeNss caCertificates ]);
  extraCommands = ''
    mkdir -p tmp && chmod 1777 tmp
    # World-writable HOME (like /tmp): the image must work when run as an
    # arbitrary uid, and npm/clojure/gitlibs all cache under $HOME.
    mkdir -p home/build && chmod 1777 home/build
    mkdir -p etc/nix
    printf 'experimental-features = nix-command flakes\nsandbox = false\nbuild-users-group =\n' > etc/nix/nix.conf
    # Workspaces can be owned by an unexpected uid (bind mounts, cross-user
    # checkouts); trust every repo so git and nix's git fetcher don't refuse.
    printf '[safe]\n\tdirectory = *\n' > etc/gitconfig
    mkdir -p nix/var/nix/gcroots
    # fakeNss points /etc/passwd and /etc/group at absolute /nix/store paths.
    # runc >= 1.2 (openat2 path hardening, post-CVE-2024-21626) refuses those
    # symlinks while resolving the user for `docker exec -u <uid>` — failing
    # with "openat etc/group: path escapes from parent" — so any runner that
    # enters the container as a uid (e.g. Jenkins durable-task) breaks on
    # newer-runc hosts while older-runc hosts work. Materialise them as real
    # files so path resolution stays within the rootfs on every runc version.
    rm -f etc/passwd etc/group
    printf 'root:x:0:0:root:/root:/bin/sh\nbuild:x:1000:1000:build:/home/build:/bin/sh\nnobody:x:65534:65534:nobody:/:/bin/sh\n' > etc/passwd
    printf 'root:x:0:\nbuild:x:1000:\nnobody:x:65534:\n' > etc/group
  '';
  config = {
    # Pure toolchain image: no entrypoint. `docker run -it bardigan-cay-build`
    # drops into a shell; runners override the command as they see fit.
    Cmd = [ "bash" ];
    WorkingDir = "/home/build";
    Env = [
      "PATH=${pkgs.lib.makeBinPath imageToolchain}:/bin:/usr/bin"
      "HOME=/home/build"
      "JAVA_HOME=${jdk}"
      # Public roots by default. To trust a private CA at runtime, mount the
      # PEM and override the relevant variable(s) per tool — e.g. SSL_CERT_FILE
      # for skopeo, NIX_SSL_CERT_FILE for nix, CURL_CA_BUNDLE for curl.
      "SSL_CERT_FILE=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"
      "NIX_SSL_CERT_FILE=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"
      "CURL_CA_BUNDLE=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"
      "GIT_SSL_CAINFO=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"
      "NIX_REMOTE="
      "LANG=C.UTF-8"
      "LC_ALL=C.UTF-8"
    ];
  };
}
