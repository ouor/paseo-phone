// Pure string helpers for the phone runtime UI. No React, no native imports, so vitest runs them anywhere.
import type { LogKind, NetworkAddress } from "../../modules/paseo-phone-runtime";

const SSH_USER = "root";

export function buildSshCommand(address: string, port: number): string {
  return `ssh -p ${port} ${SSH_USER}@${address}`;
}

/** Paste target for Paseo desktop's Remote SSH host. The daemon port is the default 6767, so `?daemonPort=` is left out. */
export function buildDesktopSshUri(address: string, port: number): string {
  return `ssh://${SSH_USER}@${address}:${port}`;
}

export function buildSshConfig(alias: string, address: string, port: number): string {
  return [`Host ${alias}`, `  HostName ${address}`, `  Port ${port}`, `  User ${SSH_USER}`].join(
    "\n",
  );
}

/** `Pixel 8` -> `pixel-8`. Used as the `Host` alias in an SSH config. */
export function toSshHostAlias(label: string): string {
  const alias = label
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return alias || "paseo-phone";
}

/** Prefers the kind the user picked, then Wi-Fi, then whatever is there. */
export function pickAddress(
  addresses: readonly NetworkAddress[],
  preferred: NetworkAddress["kind"] | null,
): NetworkAddress | null {
  return (
    addresses.find((entry) => entry.kind === preferred) ??
    addresses.find((entry) => entry.kind === "wifi") ??
    addresses[0] ??
    null
  );
}

export function formatMegabytes(bytes: number): string {
  if (bytes >= 1_000_000_000) {
    return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  }
  return `${Math.round(bytes / 1_000_000)} MB`;
}

/** Core reports no-space as `free 2.1 GB, need 4.0 GB` (docs/phone-dev-machine/core/core-linux.md). */
export function parseNoSpaceMessage(message: string): { free: string; needed: string } | null {
  const match = /free\s+([\d.]+\s*[KMGT]?B)\s*,\s*need\s+([\d.]+\s*[KMGT]?B)/i.exec(message);
  return match ? { free: match[1], needed: match[2] } : null;
}

export interface PhoneNotificationIntent {
  confirmStop: boolean;
  logs: LogKind | null;
}

const LOG_KINDS: readonly LogKind[] = ["daemon", "ssh", "setup"];

/** Reads the query the Linux notification appends: `?confirmStop=1`, `?logs=daemon`. */
export function readPhoneNotificationIntent(params: {
  confirmStop?: string | string[];
  logs?: string | string[];
}): PhoneNotificationIntent {
  const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);
  const logs = first(params.logs);
  return {
    confirmStop: first(params.confirmStop) === "1",
    logs: LOG_KINDS.find((kind) => kind === logs) ?? null,
  };
}
