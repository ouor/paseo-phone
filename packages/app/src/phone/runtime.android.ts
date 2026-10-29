import { useSyncExternalStore } from "react";
import {
  addStateListener,
  getState,
  isPhoneRuntime,
  start,
  stop,
  type PhoneRuntimeState,
} from "../../modules/paseo-phone-runtime";

export { isPhoneRuntime };

// Native getState() returns a fresh object per call; useSyncExternalStore needs a stable snapshot.
let snapshot: PhoneRuntimeState = getState();
const listeners = new Set<() => void>();

if (isPhoneRuntime) {
  addStateListener((next) => {
    snapshot = next;
    for (const listener of listeners) listener();
  });
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot(): PhoneRuntimeState {
  return snapshot;
}

/** Live runtime state, or null on a build without the phone runtime (a plain Android Paseo build). */
export function usePhoneRuntimeState(): PhoneRuntimeState | null {
  const state = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  return isPhoneRuntime ? state : null;
}

function waitForLinuxToSettle(): Promise<void> {
  return new Promise((resolve) => {
    const subscription = addStateListener((next) => {
      if (
        next.status !== "ready" ||
        next.linux.state === "stopped" ||
        next.linux.state === "error"
      ) {
        subscription.remove();
        resolve();
      }
    });
  });
}

/**
 * Stop then start, so both proot sessions pick up a new bind (the exchange folder after storage
 * access is granted). The core accepts stop() immediately and reports completion through state.
 * shortcut: composes stop/start in the UI because the core has no restartLinux(); move it there if one lands.
 */
export async function restartLinux(): Promise<void> {
  if (snapshot.status === "ready" && snapshot.linux.state === "running") {
    const settled = waitForLinuxToSettle();
    await stop();
    await settled;
  }
  await start();
}
