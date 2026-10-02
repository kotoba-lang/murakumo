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
        onboard.enable = true;
        report.enable = true;
      };
      networking.networkmanager.enable = true;
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
assert builtins.hasAttr "murakumo-report" services;
assert builtins.hasAttr "murakumo-report" timers;
assert lib.hasInfix "node report" services.murakumo-report.script;
assert lib.hasInfix "--once" services.murakumo-report.script;
assert timers.murakumo-report.timerConfig.OnUnitActiveSec == "60s";
assert services.murakumo-report.serviceConfig.SuccessExitStatus == "4";
assert !(builtins.hasAttr "murakumo-report" disabled.systemd.services);
assert builtins.hasAttr "murakumo-onboard" services;
assert lib.hasInfix "node onboard" services.murakumo-onboard.script;
assert lib.hasInfix "--state-dir /var/lib/murakumo/onboard" services.murakumo-onboard.script;
assert lib.hasInfix "--keyfile-dir /etc/NetworkManager/system-connections" services.murakumo-onboard.script;
assert !(lib.hasInfix "--play-cmd" services.murakumo-onboard.script);
assert services.murakumo-onboard.environment.MURAKUMO_ONBOARD_DIR == "/var/lib/murakumo/onboard";
assert builtins.elem "!/var/lib/murakumo/onboard/closed" services.murakumo-onboard.unitConfig.ConditionPathExists;
assert services.murakumo-onboard.serviceConfig.RestartPreventExitStatus == "4";
assert !(builtins.hasAttr "murakumo-claim" disabled.systemd.services);
assert !(builtins.hasAttr "murakumo-onboard" disabled.systemd.services);
assert !(builtins.hasAttr "murakumo-idle" disabled.systemd.services);
true
