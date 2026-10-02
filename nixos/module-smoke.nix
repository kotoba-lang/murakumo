let
  lib = (import <nixpkgs> { }).lib;
  enabled = (import <nixpkgs/nixos> {
    configuration = {
      imports = [ ./node.nix ];
      system.stateVersion = "25.05";
      services.murakumoNode = {
        enable = true;
        cliPath = "/opt/murakumo-cli/current/murakumo";
        claimResponder.enable = true;
        participation.enable = true;
        nodeName = "smoke-node";
        model = "smoke-model";
      };
    };
  }).config;
  disabled = (import <nixpkgs/nixos> {
    configuration = {
      imports = [ ./node.nix ];
      system.stateVersion = "25.05";
    };
  }).config;
  services = enabled.systemd.services;
  timers = enabled.systemd.timers;
in
assert builtins.hasAttr "murakumo-claim" services;
assert builtins.hasAttr "murakumo-claim" timers;
assert builtins.hasAttr "murakumo-idle" services;
assert enabled.services.murakumoNode.participation.enable;
assert lib.hasInfix "--idle-only" services.murakumo-idle.script;
assert services.murakumo-claim.environment.MURAKUMO_NODE_IDENTITY_FILE == "/var/lib/murakumo/device-identity.json";
assert !(builtins.hasAttr "murakumo-claim" disabled.systemd.services);
assert !(builtins.hasAttr "murakumo-idle" disabled.systemd.services);
true
