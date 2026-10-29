import { useSyncExternalStore } from "react";
import {
  addStateListener,
  getState,
  isPhoneRuntime,
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
