{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    devenv.url = "github:cachix/devenv";
    clj-nix.url = "github:jlesquembre/clj-nix";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";
  };
  outputs = {
    self,
    nixpkgs,
    devenv,
    clj-nix,
    ...
  } @ inputs: let
    supportedSystems = ["x86_64-linux" "aarch64-linux"];
    pkgsBySystem = nixpkgs.lib.getAttrs supportedSystems nixpkgs.legacyPackages;
    forAllPkgs = fn: nixpkgs.lib.mapAttrs (_: pkgs: (fn pkgs)) pkgsBySystem;
  in {
    formatter = forAllPkgs (pkgs: pkgs.alejandra);
    devShells = forAllPkgs (pkgs': let
      system = pkgs'.stdenv.hostPlatform.system;
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };
    in {
      default = devenv.lib.mkShell {
        inherit inputs pkgs;
        modules = [
          (
            {
              config,
              pkgs,
              ...
            }: {
              # https://devenv.sh/reference/options/
              packages = [
                pkgs.git
                pkgs.babashka
                pkgs.jet
                pkgs.neovim
                pkgs.cljfmt
                #pkgs.vscode
                (pkgs.vscode-with-extensions.override {
                  vscodeExtensions = [
                    pkgs.vscode-extensions.betterthantomorrow.calva
                    pkgs.vscode-extensions.vscodevim.vim
                    pkgs.vscode-extensions.jnoortheen.nix-ide
                  ];
                })
              ];

              languages.clojure.enable = true;

              # N.B. picks up quotes and inline comments
              dotenv.enable = true;

              env = {
                ENV = "DEV";
              };

              scripts.format.exec = ''
                nix fmt .
                cljfmt fix src
                cljfmt fix deps.edn
              '';
              scripts.lock.exec = ''
                nix flake lock
                nix run .#deps-lock
              '';
              scripts.update.exec = ''
                nix flake update
                nix run .#deps-lock
              '';
              scripts.build.exec = ''
                nix build .
              '';
              scripts.repl.exec = ''
                clj -Sdeps '{:deps {cider/cider-nrepl {:mvn/version "0.50.0"} }}' -M -m nrepl.cmdline --middleware "[cider.nrepl/cider-middleware]" -b "0.0.0.0" -p 9998
              '';
              scripts.outdated.exec = ''
                clojure -Aoutdated -M -m "antq.core"
              '';
              scripts.scripts.exec = ''
                echo ${builtins.concatStringsSep " " (builtins.attrNames config.scripts)}
              '';

              enterShell = ''
                # start editor
                echo    "===================================================="
                echo "Type \`scripts\` for help and a list of available scripts."
                echo -n "Available scripts: "
                scripts
                echo    "===================================================="
                echo
                echo "Starting editor and user shell..."
                export SHELL=$OLDSHELL
                code . &>/dev/null
              '';
            }
          )
        ];
      };
    });
    packages = forAllPkgs (pkgs: let
      system = pkgs.stdenv.hostPlatform.system;
    in {
      deps-lock = clj-nix.packages.${system}.deps-lock;
      container = pkgs.dockerTools.buildLayeredImage {
        name = "boostbox";
        tag = "latest";
        config = {
          Entrypoint = ["${self.packages.${system}.default}/bin/boostbox"];
          ExposedPorts = {
            "8080" = {};
          };
        };
      };
      default = clj-nix.lib.mkCljApp {
        inherit pkgs;
        modules = [
          {
            projectSrc = ./.;
            name = "com.noblepayne/boostbox";
            main-ns = "boostbox.boostbox";
          }
        ];
      };
    });
  };
}
