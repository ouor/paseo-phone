import { useEffect, useState, type ReactNode } from "react";
import { useLatchedBoolean } from "@/hooks/use-latched-boolean";
import { useHostRuntimeIsConnected } from "@/runtime/host-runtime";
import { StartupSplashScreen } from "@/screens/startup-splash-screen";
import type { PhoneRuntimeState } from "../../modules/paseo-phone-runtime";
import { registerPhoneTranslations } from "./i18n/register";
import { LinuxSetupScreen } from "./linux-setup-screen.android";
import { LinuxStoppedCalloutSource } from "./linux-stopped-callout-source.android";
import { isPhoneRuntime, usePhoneRuntimeState } from "./runtime.android";

registerPhoneTranslations();

// Upstream gives up waiting for a host after 5s and routes to Welcome; a cold proot start takes longer.
const HOST_WAIT_TIMEOUT_MS = 60_000;

/**
 * Wraps AppShell (architecture §7.3). While Linux is being checked, installed or updated, the setup
 * screen replaces the whole app so the router never picks Welcome or a host. After that it holds
 * the splash until this phone's host is online, once, then gets out of the way.
 */
export function PhoneRuntimeGate({ children }: { children: ReactNode }) {
  if (!isPhoneRuntime) {
    return children;
  }
  return <PhoneGate>{children}</PhoneGate>;
}

function PhoneGate({ children }: { children: ReactNode }) {
  const state = usePhoneRuntimeState();
  if (!state) {
    return children;
  }
  if (state.status !== "ready") {
    return <LinuxSetupScreen state={state} />;
  }
  return <ReadyGate state={state}>{children}</ReadyGate>;
}

function ReadyGate({
  state,
  children,
}: {
  state: Extract<PhoneRuntimeState, { status: "ready" }>;
  children: ReactNode;
}) {
  const serverId = state.daemon.serverId;
  const hostOnline = useHostRuntimeIsConnected(serverId ?? "");
  const linuxComingUp = state.linux.state === "starting" || state.linux.state === "running";
  const [timedOut, setTimedOut] = useState(false);
  const released = useLatchedBoolean(!linuxComingUp || hostOnline || timedOut);

  useEffect(() => {
    if (released) return;
    const handle = setTimeout(() => setTimedOut(true), HOST_WAIT_TIMEOUT_MS);
    return () => clearTimeout(handle);
  }, [released]);

  if (!released) {
    return <StartupSplashScreen />;
  }
  return (
    <>
      <LinuxStoppedCalloutSource state={state} />
      {children}
    </>
  );
}
