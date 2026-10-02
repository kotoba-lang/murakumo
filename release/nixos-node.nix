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
    console = {
      enable = mkEnableOption "the box's own status screen with a claim QR code (murakumo node console), drawn on a terminal";
      tty = mkOption {
        type = types.str;
        default = "tty1";
        description = "Terminal to draw on: tty1 for a monitor, or the serial console (ttyAMA0 on an aarch64 VM, ttyS0 on x86).";
      };
      stateDir = mkOption {
        type = types.str;
        default = "/var/lib/murakumo";
        description = "Where the label, the last heartbeat result and the onboarding state live.";
      };
    };
    report = {
      enable = mkEnableOption "periodic signed heartbeats to the console, so a claimed device shows when it was last seen";
      intervalSeconds = mkOption {
        type = types.ints.between 10 600;
        default = 60;
        description = "How often to report. The console counts a device as live for a few multiples of 60 s, so keep this at or under that.";
      };
    };
    onboard = {
      enable = mkEnableOption ''
        Wi-Fi onboarding by sound (ADR-0243) for a box that has a speaker and a microphone
        but no screen. The unit exists only until the box is claimed: it needs the label
        secret in the state directory and stops for good once the claim retires it
      '';
      stateDir = mkOption {
        type = types.str;
        default = "/var/lib/murakumo/onboard";
        description = "Private directory holding the label secret, the onboarding key and the received (encrypted) message.";
      };
      mode = mkOption {
        type = types.enum [ "quiet" "soft" ];
        default = "quiet";
        description = "quiet carries data in ultrasound; soft carries it audibly at low volume.";
      };
      ttlMinutes = mkOption {
        type = types.ints.between 1 120;
        default = 15;
        description = "How long one provisioning window stays open.";
      };
      playCommand = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "Command that plays raw f32le 48 kHz mono PCM from stdin. Null means aplay.";
      };
      recordCommand = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "Command that writes raw f32le 48 kHz mono PCM to stdout. Null means arecord.";
      };
    };
  };

  config = mkIf cfg.enable {
    assertions = [
      { assertion = lib.hasPrefix "/" cfg.cliPath;
        message = "services.murakumoNode.cliPath must be an absolute path to the installed launcher."; }
      { assertion = lib.hasPrefix "/" cfg.identityFile;
        message = "services.murakumoNode.identityFile must be an absolute runtime path."; }
      { assertion = !cfg.participation.enable || (cfg.nodeName != "" && cfg.model != "");
        message = "Idle participation requires an explicit nodeName and exact served model ID."; }
      { assertion = !cfg.onboard.enable || lib.hasPrefix "/" cfg.onboard.stateDir;
        message = "services.murakumoNode.onboard.stateDir must be an absolute path."; }
      { assertion = !cfg.onboard.enable || config.networking.networkmanager.enable;
        message = "Wi-Fi onboarding hands the profile to NetworkManager; enable networking.networkmanager."; }
    ];

    systemd.tmpfiles.rules = lib.optional cfg.onboard.enable "d ${cfg.onboard.stateDir} 0700 root root -";

    # Not ordered after network-online: this unit exists to get the box online. It is only
    # started while the box is unclaimed (the label secret exists, the claim marker does not).
    systemd.services.murakumo-onboard = mkIf cfg.onboard.enable (common // {
      description = "Take a Wi-Fi profile handed over by sound (ADR-0243)";
      wantedBy = [ "multi-user.target" ];
      after = [ "sound.target" "NetworkManager.service" ];
      wants = [ ];
      path = [ pkgs.nodejs pkgs.alsa-utils pkgs.networkmanager ];
      environment = common.environment // { MURAKUMO_ONBOARD_DIR = cfg.onboard.stateDir; };
      unitConfig.ConditionPathExists = [ "${cfg.onboard.stateDir}/label-secret" "!${cfg.onboard.stateDir}/closed" ];
      script = ''exec ${cli} node onboard --state-dir ${lib.escapeShellArg cfg.onboard.stateDir} --keyfile-dir /etc/NetworkManager/system-connections --mode ${cfg.onboard.mode} --ttl-minutes ${toString cfg.onboard.ttlMinutes} --site ${lib.escapeShellArg cfg.siteUrl}${lib.optionalString (cfg.onboard.playCommand != null) " --play-cmd ${lib.escapeShellArg cfg.onboard.playCommand}"}${lib.optionalString (cfg.onboard.recordCommand != null) " --record-cmd ${lib.escapeShellArg cfg.onboard.recordCommand}"}'';
      serviceConfig = common.serviceConfig // {
        Type = "simple";
        Restart = "on-failure";
        RestartSec = "30s";
        # exit 4 means the window closed (time, attempts, or no label secret): do not reopen it by restarting
        RestartPreventExitStatus = "4";
        ReadWritePaths = [ cfg.onboard.stateDir "/etc/NetworkManager/system-connections" ];
        SupplementaryGroups = [ "audio" ];
      };
    });

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

    # Takes over its terminal from getty, the way a kiosk does: the screen is the box's own and has no login.
    systemd.services.murakumo-console = mkIf cfg.console.enable (common // {
      description = "Show this box's Murakumo status and claim code on its screen";
      wantedBy = [ "multi-user.target" ];
      # Not after network-online: the screen is most useful exactly when the network is not up yet.
      after = [ "systemd-user-sessions.service" "getty@${cfg.console.tty}.service" "serial-getty@${cfg.console.tty}.service" ];
      wants = [ ];
      conflicts = [ "getty@${cfg.console.tty}.service" "serial-getty@${cfg.console.tty}.service" ];
      environment = common.environment // {
        MURAKUMO_STATE_DIR = cfg.console.stateDir;
        TERM = if builtins.match "tty[0-9]+" cfg.console.tty != null then "linux" else "vt100";
      };
      script = ''exec ${cli} node console --state-dir ${lib.escapeShellArg cfg.console.stateDir} --site ${lib.escapeShellArg cfg.siteUrl}'';
      serviceConfig = common.serviceConfig // {
        Type = "simple";
        Restart = "always";
        RestartSec = "5s";
        StandardInput = "tty";
        StandardOutput = "tty";
        TTYPath = "/dev/${cfg.console.tty}";
        TTYReset = true;
        TTYVHangup = true;
        TTYVTDisallocate = builtins.match "tty[0-9]+" cfg.console.tty != null;
      };
    });

    systemd.services.murakumo-report = mkIf cfg.report.enable (common // {
      description = "Send a signed Murakumo device heartbeat";
      script = ''exec ${cli} node report --site ${lib.escapeShellArg cfg.siteUrl} --once'';
      serviceConfig = common.serviceConfig // {
        Type = "oneshot";
        # exit 4: not claimed yet. Expected until the claim goes through, not a failure to alarm on.
        SuccessExitStatus = "4";
      };
    });
    systemd.timers.murakumo-report = mkIf cfg.report.enable {
      description = "Report a Murakumo device heartbeat";
      wantedBy = [ "timers.target" ];
      timerConfig = {
        OnBootSec = "30s";
        OnUnitActiveSec = "${toString cfg.report.intervalSeconds}s";
        Unit = "murakumo-report.service";
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
