{ config, lib, pkgs, ... }:

let
  cfg = config.services.murakumoNode;
  inherit (lib) mkEnableOption mkIf mkOption types;
  cli = lib.escapeShellArg cfg.cliPath;
  common = {
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];
    path = [ pkgs.nodejs ];
    environment.MURAKUMO_NODE_IDENTITY_FILE = cfg.identityFile;
    serviceConfig = {
      User = "root";
      NoNewPrivileges = true;
      PrivateTmp = true;
      ProtectSystem = "strict";
      ProtectHome = true;
    };
  };
in
{
  options.services.murakumoNode = {
    enable = mkEnableOption "factory-provisioned Murakumo node services";
    cliPath = mkOption {
      type = types.str;
      default = "";
      description = "Absolute path to the installed murakumo launcher. Install outside /home because services cannot read /home.";
    };
    identityFile = mkOption {
      type = types.str;
      default = "/var/lib/murakumo/device-identity.json";
      description = "Runtime path to the private factory identity. Its contents must never be placed in the Nix store.";
    };
    siteUrl = mkOption {
      type = types.str;
      default = "https://murakumo.cloud";
      description = "Buyer claim service URL.";
    };
    nodeName = mkOption { type = types.str; default = ""; };
    model = mkOption { type = types.str; default = ""; };
    localUrl = mkOption { type = types.str; default = "http://127.0.0.1:11434/v1"; };
    baseUrl = mkOption { type = types.str; default = "https://api.murakumo.cloud"; };
    authnUrl = mkOption { type = types.str; default = "https://auth.murakumo.cloud"; };
    claimResponder.enable = mkEnableOption "periodic device claim responses";
    participation.enable = mkEnableOption "idle-only Community job participation";
  };

  config = mkIf cfg.enable {
    assertions = [
      { assertion = lib.hasPrefix "/" cfg.cliPath;
        message = "services.murakumoNode.cliPath must be an absolute path to the installed launcher."; }
      { assertion = lib.hasPrefix "/" cfg.identityFile;
        message = "services.murakumoNode.identityFile must be an absolute runtime path."; }
      { assertion = !cfg.participation.enable || (cfg.nodeName != "" && cfg.model != "");
        message = "Idle participation requires an explicit nodeName and exact served model ID."; }
    ];

    systemd.services.murakumo-claim = mkIf cfg.claimResponder.enable (common // {
      description = "Answer a pending Murakumo buyer device claim";
      script = ''exec ${cli} node claim-once --site ${lib.escapeShellArg cfg.siteUrl}'';
      serviceConfig = common.serviceConfig // { Type = "oneshot"; };
    });
    systemd.timers.murakumo-claim = mkIf cfg.claimResponder.enable {
      description = "Poll for a pending Murakumo device claim";
      wantedBy = [ "timers.target" ];
      timerConfig = {
        OnBootSec = "20s";
        OnUnitActiveSec = "20s";
        Unit = "murakumo-claim.service";
      };
    };

    systemd.services.murakumo-idle = mkIf cfg.participation.enable (common // {
      description = "Serve Murakumo Community jobs only while the host is idle";
      wantedBy = [ "multi-user.target" ];
      script = ''exec ${cli} node join --name ${lib.escapeShellArg cfg.nodeName} --model ${lib.escapeShellArg cfg.model} --local-url ${lib.escapeShellArg cfg.localUrl} --base ${lib.escapeShellArg cfg.baseUrl} --authn ${lib.escapeShellArg cfg.authnUrl} --idle-only'';
      serviceConfig = common.serviceConfig // {
        Type = "simple";
        Restart = "on-failure";
        RestartSec = "30s";
      };
    });
  };
}
