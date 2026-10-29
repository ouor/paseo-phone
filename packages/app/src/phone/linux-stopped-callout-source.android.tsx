import { useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert } from "react-native";
import { useSidebarCallouts } from "@/contexts/sidebar-callout-context";
import { useStableEvent } from "@/hooks/use-stable-event";
import { buildSettingsHostSectionRoute, buildSettingsRoute } from "@/utils/host-routes";
import { start, type PhoneRuntimeState } from "../../modules/paseo-phone-runtime";

const CALLOUT_ID = "phone-linux-stopped";

/** "Linux is stopped" in the sidebar, from the moment Linux stops until it is running again. */
export function LinuxStoppedCalloutSource({
  state,
}: {
  state: Extract<PhoneRuntimeState, { status: "ready" }>;
}) {
  const { t } = useTranslation();
  const callouts = useSidebarCallouts();
  const router = useRouter();
  const linux = state.linux.state;
  const serverId = state.daemon.serverId;

  // App start passes through `starting` too; only a stop seen in this session raises the callout,
  // and it stays through the following `starting` so the Start button can say so.
  const [down, setDown] = useState(false);
  if (!down && (linux === "stopped" || linux === "error")) setDown(true);
  if (down && linux === "running") setDown(false);

  const handleStart = useStableEvent(() => {
    start().catch((error: Error) =>
      Alert.alert(t("settings.host.linux.startFailed"), error.message),
    );
  });
  const handleDetails = useStableEvent(() => {
    router.navigate(
      serverId ? buildSettingsHostSectionRoute(serverId, "host") : buildSettingsRoute(),
    );
  });

  const starting = linux === "starting";
  const crashed = linux === "error";

  useEffect(() => {
    if (!down) return;
    return callouts.show({
      id: CALLOUT_ID,
      title: t(
        crashed
          ? "sidebar.callouts.linuxStopped.crashedTitle"
          : "sidebar.callouts.linuxStopped.title",
      ),
      description: t("sidebar.callouts.linuxStopped.description"),
      variant: crashed ? "error" : "default",
      dismissible: false,
      priority: 200,
      actions: [
        {
          label: t(
            starting
              ? "sidebar.callouts.linuxStopped.starting"
              : "sidebar.callouts.linuxStopped.start",
          ),
          onPress: handleStart,
          variant: "primary",
          disabled: starting,
        },
        { label: t("sidebar.callouts.linuxStopped.details"), onPress: handleDetails },
      ],
      testID: "phone-linux-stopped-callout",
    });
  }, [callouts, crashed, down, handleDetails, handleStart, starting, t]);

  return null;
}
