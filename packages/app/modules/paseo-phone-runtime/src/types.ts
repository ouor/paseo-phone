// Native core <-> UI contract. Source of truth: docs/phone-dev-machine/architecture/architecture.md §7.1.

export type SetupStep = "download" | "extract" | "configure" | "packages" | "paseo" | "start";

export type SetupErrorCode = "offline" | "no-space" | "checksum" | "command-failed" | "unknown";

export interface SetupProgress {
  done: number;
  total: number;
  unit: "bytes" | "packages";
}

export type ServiceErrorCode =
  | "crashed"
  | "killed-by-system" // proot ended with a SIGKILL we did not send (phantom process killer, low memory)
  | "not-responding"
  | "start-failed"
  | "restart-limit";

export type ServiceState =
  | { state: "starting" }
  | { state: "running" }
  | { state: "stopping" }
  | { state: "stopped" }
  | { state: "error"; code: ServiceErrorCode; message: string };

export interface NetworkAddress {
  kind: "wifi" | "tailscale";
  address: string; // IPv4
}

export type ExchangeStatus =
  | { status: "mounted" }
  | { status: "no-permission" }
  | { status: "restart-required" }
  | { status: "error"; message: string };

export interface DeviceChecks {
  batteryOptimized: boolean; // true = subject to battery optimization (warn)
  phantomProcessMonitor: "disabled" | "enabled" | "unknown";
  notificationsAllowed: boolean;
}

export type PhoneRuntimeState =
  | { status: "checking" }
  | {
      status: "setup";
      mode: "install" | "update";
      step: SetupStep;
      progress: SetupProgress | null;
      targetVersion: string; // Paseo daemon version being installed
    }
  | {
      status: "setup-failed";
      mode: "install" | "update";
      step: SetupStep;
      code: SetupErrorCode;
      message: string; // raw, not translated
      canStartPrevious: boolean; // update failed and the previous daemon can still start
    }
  | {
      status: "ready";
      linux: ServiceState; // overall; when stopped/error, daemon.state and ssh.state carry no meaning
      daemon: ServiceState & { serverId: string | null; version: string; listen: "127.0.0.1:6767" };
      ssh: { enabled: boolean; port: 2222; state: ServiceState; hostKeyFingerprints: string[] };
      keepAwake: boolean;
      distro: string; // "Debian 13"
      addresses: NetworkAddress[];
      exchange: ExchangeStatus & {
        phonePathLabel: string;
        phonePath: string;
        linuxPath: "/root/exchange";
      };
      device: DeviceChecks;
    };

export interface AuthorizedKey {
  type: string; // "ssh-ed25519"
  comment: string; // "" when absent
  fingerprint: string; // "SHA256:…"
}

export type LogKind = "daemon" | "ssh" | "setup";

export type PhoneRuntimeErrorCode =
  | "busy"
  | "not-installed"
  | "invalid-key"
  | "private-key"
  | "duplicate-key"
  | "not-found"
  | "io-error"
  | "unavailable"; // called on a build without the native runtime (iOS, web, non-phone variant)
