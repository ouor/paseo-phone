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

export interface PhoneNotificationIntent {
  confirmStop: boolean;
  logs: LogKind | null;
}

const LOG_KINDS: readonly LogKind[] = ["daemon", "ssh", "setup"];

type QueryValue = string | string[] | undefined;

/**
 * Reads the query the Linux notification appends: `?confirmStop=1`, `?logs=daemon`. The caller
 * looks the values up by `PHONE_NOTIFICATION_QUERY`, so this file stays free of native imports.
 */
export function readPhoneNotificationIntent(
  confirmStop: QueryValue,
  logs: QueryValue,
): PhoneNotificationIntent {
  const first = (value: QueryValue) => (Array.isArray(value) ? value[0] : value);
  const logKind = first(logs);
  return {
    confirmStop: first(confirmStop) === "1",
    logs: LOG_KINDS.find((kind) => kind === logKind) ?? null,
  };
}
