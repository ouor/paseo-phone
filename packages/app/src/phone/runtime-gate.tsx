import type { ReactNode } from "react";

/** Off Android there is no phone runtime to wait for. See runtime-gate.android.tsx. */
export function PhoneRuntimeGate({ children }: { children: ReactNode }) {
  return children;
}
