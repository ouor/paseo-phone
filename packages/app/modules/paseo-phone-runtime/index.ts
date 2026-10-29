import Constants from "expo-constants";
import { requireOptionalNativeModule, type EventSubscription } from "expo-modules-core";
import type { AuthorizedKey, LogKind, PhoneRuntimeErrorCode, PhoneRuntimeState } from "./src/types";

export type * from "./src/types";

interface PhoneRuntimeNativeModule {
  getState(): PhoneRuntimeState;
  addListener(
    eventName: "onStateChange",
    listener: (event: { state: PhoneRuntimeState }) => void,
  ): EventSubscription;
  start(): Promise<void>;
  stop(): Promise<void>;
  restartLinux(): Promise<void>;
  restartDaemon(): Promise<void>;
  retrySetup(): Promise<void>;
  startPrevious(): Promise<void>;
  reinstall(): Promise<void>;
  setSshEnabled(enabled: boolean): Promise<void>;
  setKeepAwake(enabled: boolean): Promise<void>;
  openBatterySettings(): Promise<void>;
  requestStoragePermission(): Promise<void>;
  requestNotificationPermission(): Promise<boolean>;
  openExchangeFolder(): Promise<void>;
  listAuthorizedKeys(): Promise<AuthorizedKey[]>;
  addAuthorizedKey(text: string): Promise<AuthorizedKey>;
  removeAuthorizedKey(fingerprint: string): Promise<void>;
  getLogs(kind: LogKind, maxBytes: number | null): Promise<{ path: string; contents: string }>;
}

const native = requireOptionalNativeModule<PhoneRuntimeNativeModule>("PaseoPhoneRuntime");

export const isPhoneRuntime: boolean =
  native !== null && Constants.expoConfig?.extra?.phoneRuntime === true;

const KNOWN_CODES: readonly PhoneRuntimeErrorCode[] = [
  "busy",
  "not-installed",
  "invalid-key",
  "private-key",
  "duplicate-key",
  "not-found",
  "io-error",
  "unavailable",
];

export class PhoneRuntimeError extends Error {
  readonly code: PhoneRuntimeErrorCode;

  constructor(code: PhoneRuntimeErrorCode, message: string) {
    super(message);
    this.name = "PhoneRuntimeError";
    this.code = code;
  }
}

function requireNative(): PhoneRuntimeNativeModule {
  if (!isPhoneRuntime || !native) {
    throw new PhoneRuntimeError("unavailable", "Phone runtime is not available on this build");
  }
  return native;
}

function toPhoneRuntimeError(error: unknown): PhoneRuntimeError {
  const code = (error as { code?: unknown } | null)?.code;
  const message = error instanceof Error ? error.message : String(error);
  const known = KNOWN_CODES.find((candidate) => candidate === code);
  return new PhoneRuntimeError(known ?? "io-error", message);
}

async function call<T>(run: (module: PhoneRuntimeNativeModule) => Promise<T>): Promise<T> {
  const module = requireNative();
  try {
    return await run(module);
  } catch (error) {
    throw toPhoneRuntimeError(error);
  }
}

const CHECKING: PhoneRuntimeState = { status: "checking" };

/** Latest snapshot. Off the phone runtime it stays `checking`; callers gate on `isPhoneRuntime` first. */
export function getState(): PhoneRuntimeState {
  return isPhoneRuntime && native ? native.getState() : CHECKING;
}

export function addStateListener(listener: (state: PhoneRuntimeState) => void): {
  remove(): void;
} {
  if (!isPhoneRuntime || !native) {
    return { remove() {} };
  }
  return native.addListener("onStateChange", (event) => listener(event.state));
}

export function start(): Promise<void> {
  return call((m) => m.start());
}

export function stop(): Promise<void> {
  return call((m) => m.stop());
}

/** Stop then start in one serialized command. Resolves once accepted; progress arrives as state. */
export function restartLinux(): Promise<void> {
  return call((m) => m.restartLinux());
}

export function restartDaemon(): Promise<void> {
  return call((m) => m.restartDaemon());
}

export function retrySetup(): Promise<void> {
  return call((m) => m.retrySetup());
}

export function startPrevious(): Promise<void> {
  return call((m) => m.startPrevious());
}

export function reinstall(): Promise<void> {
  return call((m) => m.reinstall());
}

export function setSshEnabled(enabled: boolean): Promise<void> {
  return call((m) => m.setSshEnabled(enabled));
}

export function setKeepAwake(enabled: boolean): Promise<void> {
  return call((m) => m.setKeepAwake(enabled));
}

export function openBatterySettings(): Promise<void> {
  return call((m) => m.openBatterySettings());
}

export function requestStoragePermission(): Promise<void> {
  return call((m) => m.requestStoragePermission());
}

export function requestNotificationPermission(): Promise<boolean> {
  return call((m) => m.requestNotificationPermission());
}

export function openExchangeFolder(): Promise<void> {
  return call((m) => m.openExchangeFolder());
}

export function listAuthorizedKeys(): Promise<AuthorizedKey[]> {
  return call((m) => m.listAuthorizedKeys());
}

export function addAuthorizedKey(text: string): Promise<AuthorizedKey> {
  return call((m) => m.addAuthorizedKey(text));
}

export function removeAuthorizedKey(fingerprint: string): Promise<void> {
  return call((m) => m.removeAuthorizedKey(fingerprint));
}

export function getLogs(
  kind: LogKind,
  maxBytes?: number,
): Promise<{ path: string; contents: string }> {
  return call((m) => m.getLogs(kind, maxBytes ?? null));
}
