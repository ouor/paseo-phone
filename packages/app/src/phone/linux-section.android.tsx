import * as Clipboard from "expo-clipboard";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert as NativeAlert, Text, View } from "react-native";
import { StyleSheet } from "react-native-unistyles";
import { AdaptiveModalSheet, type SheetHeader } from "@/components/adaptive-modal-sheet";
import { SettingsCard, SettingsRow, SettingsSection } from "@/components/settings";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { StatusBadge, type StatusBadgeVariant } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { useToast } from "@/contexts/toast-api-context";
import { useFetchQuery } from "@/data/query";
import { useStableEvent } from "@/hooks/use-stable-event";
import { useSessionStore } from "@/stores/session-store";
import { CODE_SURFACE_DATASET } from "@/styles/code-surface";
import { settingsStyles } from "@/styles/settings";
import { confirmDialog } from "@/utils/confirm-dialog";
import {
  getLogs,
  openBatterySettings,
  requestNotificationPermission,
  restartDaemon,
  setKeepAwake,
  setSshEnabled,
  start,
  stop,
  type LogKind,
  type PhoneRuntimeState,
  type ServiceState,
} from "../../modules/paseo-phone-runtime";
import { readPhoneNotificationIntent } from "./format";

type ReadyState = Extract<PhoneRuntimeState, { status: "ready" }>;
type TFn = ReturnType<typeof useTranslation>["t"];

interface Badge {
  label: string;
  variant: StatusBadgeVariant;
}

function serviceBadge(service: ServiceState, t: TFn): Badge {
  switch (service.state) {
    case "running":
      return { label: t("settings.host.linux.status.running"), variant: "success" };
    case "starting":
      return { label: t("settings.host.linux.status.starting"), variant: "muted" };
    case "stopping":
      return { label: t("settings.host.linux.status.stopping"), variant: "muted" };
    case "stopped":
      return { label: t("settings.host.linux.status.stopped"), variant: "muted" };
    case "error":
      return {
        label: t(
          service.code === "not-responding"
            ? "settings.host.linux.status.notResponding"
            : "settings.host.linux.status.crashed",
        ),
        variant: "error",
      };
  }
}

const STOPPED: ServiceState = { state: "stopped" };

function StatusPill({ service }: { service: ServiceState }) {
  const { t } = useTranslation();
  const badge = serviceBadge(service, t);
  const leading = useMemo(
    () => <View style={[styles.dot, dotStyles[badge.variant]]} />,
    [badge.variant],
  );
  return <StatusBadge label={badge.label} variant={badge.variant} leading={leading} />;
}

function reportFailure(title: string) {
  return (error: Error) => NativeAlert.alert(title, error.message);
}

function countRunningAgents(serverId: string): number {
  let count = 0;
  useSessionStore.getState().sessions[serverId]?.agents.forEach((agent) => {
    if (agent.status === "running") count += 1;
  });
  return count;
}

export function LinuxSection({ state, serverId }: { state: ReadyState; serverId: string }) {
  const { t } = useTranslation();
  const router = useRouter();
  const linux = state.linux;
  const linuxUp = linux.state === "running";
  const [logsKind, setLogsKind] = useState<LogKind>("daemon");
  const [logsOpen, setLogsOpen] = useState(false);
  const [pendingSetting, setPendingSetting] = useState<"ssh" | "keepAwake" | null>(null);

  const handleStart = useStableEvent(() => {
    start().catch(reportFailure(t("settings.host.linux.startFailed")));
  });

  const handleStop = useStableEvent(async () => {
    const count = countRunningAgents(serverId);
    const confirmed = await confirmDialog({
      title: t("settings.host.linux.stopConfirm.title"),
      message:
        count > 0
          ? t("settings.host.linux.stopConfirm.message", { count })
          : t("settings.host.linux.stopConfirm.messageIdle"),
      confirmLabel: t("settings.host.linux.actions.stop"),
      cancelLabel: t("common.actions.cancel"),
      destructive: true,
    });
    if (confirmed) {
      stop().catch(reportFailure(t("settings.host.linux.stopFailed")));
    }
  });

  const handleRestartDaemon = useStableEvent(() => {
    restartDaemon().catch(reportFailure(t("settings.host.linux.restartFailed")));
  });

  const changeSetting = useStableEvent((key: "ssh" | "keepAwake", run: () => Promise<void>) => {
    setPendingSetting(key);
    run()
      .catch(reportFailure(t("settings.host.linux.settingFailed")))
      .finally(() => setPendingSetting(null));
  });
  const handleSshChange = useCallback(
    (enabled: boolean) => changeSetting("ssh", () => setSshEnabled(enabled)),
    [changeSetting],
  );
  const handleKeepAwakeChange = useCallback(
    (enabled: boolean) => changeSetting("keepAwake", () => setKeepAwake(enabled)),
    [changeSetting],
  );

  const openLogs = useCallback((kind: LogKind) => {
    setLogsKind(kind);
    setLogsOpen(true);
  }, []);
  const handleOpenLogs = useCallback(() => openLogs(logsKind), [logsKind, openLogs]);
  const handleCloseLogs = useCallback(() => setLogsOpen(false), []);

  // The Linux notification opens this page with ?confirmStop=1 (Stop action) or ?logs=daemon.
  const params = useLocalSearchParams<{ confirmStop?: string; logs?: string }>();
  const intent = readPhoneNotificationIntent(params);
  useEffect(() => {
    if (!intent.confirmStop) return;
    router.setParams({ confirmStop: undefined });
    if (linuxUp) void handleStop();
  }, [handleStop, intent.confirmStop, linuxUp, router]);
  useEffect(() => {
    if (!intent.logs) return;
    router.setParams({ logs: undefined });
    openLogs(intent.logs);
  }, [intent.logs, openLogs, router]);

  let linuxAction = (
    <Button variant="outline" onPress={handleStart}>
      {t("settings.host.linux.actions.start")}
    </Button>
  );
  if (linux.state === "running" || linux.state === "starting" || linux.state === "stopping") {
    linuxAction = (
      <Button variant="outline" onPress={handleStop} disabled={linux.state !== "running"}>
        {t(
          linux.state === "stopping"
            ? "settings.host.linux.status.stopping"
            : "settings.host.linux.actions.stop",
        )}
      </Button>
    );
  }

  const daemonError = linuxUp && state.daemon.state === "error" ? state.daemon.message : null;

  let sshHint = t("settings.host.linux.ssh.off");
  let sshError: string | null = null;
  if (state.ssh.enabled && !linuxUp) {
    sshHint = t("settings.host.linux.ssh.startsWithLinux");
  } else if (state.ssh.enabled) {
    if (state.ssh.state.state === "running") {
      sshHint = t("settings.host.linux.ssh.running", { port: state.ssh.port });
    } else if (state.ssh.state.state === "error") {
      sshHint = "";
      sshError = t("settings.host.linux.ssh.startFailed");
    } else {
      sshHint = t("settings.host.linux.status.starting");
    }
  }

  return (
    <SettingsSection
      title={t("settings.host.linux.title")}
      info={t("settings.host.linux.info")}
      testID="phone-linux-section"
    >
      <SettingsCard>
        <SettingsRow
          label={t("settings.host.linux.runtime.title")}
          hint={t("settings.host.linux.runtime.hint", { distro: state.distro })}
        >
          <View style={styles.trailing}>
            <StatusPill service={linux} />
            {linuxAction}
          </View>
        </SettingsRow>
        <SettingsRow
          label={t("settings.host.linux.daemon.title")}
          hint={t("settings.host.linux.daemon.hint", {
            listen: state.daemon.listen,
            version: state.daemon.version,
          })}
          error={daemonError}
        >
          <View style={styles.trailing}>
            <StatusPill service={linuxUp ? state.daemon : STOPPED} />
            {linuxUp ? (
              <Button variant="outline" onPress={handleRestartDaemon}>
                {t("settings.host.linux.actions.restart")}
              </Button>
            ) : null}
          </View>
        </SettingsRow>
        <SettingsRow
          label={t("settings.host.linux.ssh.title")}
          hint={sshHint || undefined}
          error={sshError}
        >
          <Switch
            value={state.ssh.enabled}
            onValueChange={handleSshChange}
            disabled={pendingSetting === "ssh"}
            accessibilityLabel={t("settings.host.linux.ssh.title")}
          />
        </SettingsRow>
        <SettingsRow
          label={t("settings.host.linux.keepAwake.title")}
          hint={t("settings.host.linux.keepAwake.hint")}
        >
          <Switch
            value={state.keepAwake}
            onValueChange={handleKeepAwakeChange}
            disabled={pendingSetting === "keepAwake"}
            accessibilityLabel={t("settings.host.linux.keepAwake.title")}
          />
        </SettingsRow>
        <SettingsRow
          label={t("settings.host.linux.logs.title")}
          hint={t("settings.host.linux.logs.hint")}
        >
          <Button variant="outline" onPress={handleOpenLogs}>
            {t("settings.host.linux.logs.open")}
          </Button>
        </SettingsRow>
      </SettingsCard>

      <LinuxAlert state={state} onOpenLogs={handleOpenLogs} />

      <PhoneLogsSheet
        visible={logsOpen}
        kind={logsKind}
        onKindChange={setLogsKind}
        onClose={handleCloseLogs}
      />
    </SettingsSection>
  );
}

/** One alert per region (docs/design.md §11), most urgent first. */
function LinuxAlert({ state, onOpenLogs }: { state: ReadyState; onOpenLogs: () => void }) {
  const { t } = useTranslation();
  const handleBattery = useCallback(() => {
    openBatterySettings().catch(reportFailure(t("settings.host.linux.battery.title")));
  }, [t]);
  const handleNotifications = useCallback(() => {
    requestNotificationPermission().catch(
      reportFailure(t("settings.host.linux.notifications.title")),
    );
  }, [t]);

  if (state.linux.state === "error") {
    return (
      <Alert
        variant="error"
        title={t("settings.host.linux.status.crashed")}
        description={state.linux.message}
      >
        <Button variant="outline" size="sm" onPress={onOpenLogs}>
          {t("settings.host.linux.logs.open")}
        </Button>
      </Alert>
    );
  }
  const killedBySystem = state.daemon.state === "error" && state.daemon.code === "killed-by-system";
  if (state.device.phantomProcessMonitor === "enabled" || killedBySystem) {
    return (
      <Alert
        variant="warning"
        title={t("settings.host.linux.phantom.title")}
        description={t("settings.host.linux.phantom.description")}
      />
    );
  }
  if (state.keepAwake && state.device.batteryOptimized) {
    return (
      <Alert
        variant="warning"
        title={t("settings.host.linux.battery.title")}
        description={t("settings.host.linux.battery.description")}
      >
        <Button variant="outline" size="sm" onPress={handleBattery}>
          {t("settings.host.linux.battery.action")}
        </Button>
      </Alert>
    );
  }
  if (!state.device.notificationsAllowed) {
    return (
      <Alert
        variant="default"
        title={t("settings.host.linux.notifications.title")}
        description={t("settings.host.linux.notifications.description")}
      >
        <Button variant="outline" size="sm" onPress={handleNotifications}>
          {t("settings.host.linux.notifications.action")}
        </Button>
      </Alert>
    );
  }
  return null;
}

const LOG_KINDS: readonly LogKind[] = ["daemon", "ssh", "setup"];
const LOGS_SNAP_POINTS = ["70%", "92%"];

function PhoneLogsSheet({
  visible,
  kind,
  onKindChange,
  onClose,
}: {
  visible: boolean;
  kind: LogKind;
  onKindChange: (kind: LogKind) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const toast = useToast();
  const header = useMemo<SheetHeader>(
    () => ({ title: t("settings.host.linux.logs.modalTitle") }),
    [t],
  );
  const options = useMemo(
    () =>
      LOG_KINDS.map((value) => ({ value, label: t(`settings.host.linux.logs.kinds.${value}`) })),
    [t],
  );
  const logsQuery = useFetchQuery({
    queryKey: ["phone-runtime-logs", kind],
    queryFn: () => getLogs(kind),
    enabled: visible,
    dataShape: "value",
    staleTimeMs: 0,
    retry: false,
  });

  let contents = t("settings.host.linux.logs.empty");
  if (logsQuery.isPending) contents = t("common.loading");
  else if (logsQuery.error) {
    contents = t("settings.host.linux.logs.loadFailed", { message: logsQuery.error.message });
  } else if (logsQuery.data?.contents) contents = logsQuery.data.contents;

  const handleCopy = useCallback(() => {
    const logs = logsQuery.data;
    if (!logs) return;
    void Clipboard.setStringAsync(`${logs.path}\n\n${logs.contents}`).then(() => toast.copied());
  }, [logsQuery.data, toast]);

  return (
    <AdaptiveModalSheet
      visible={visible}
      onClose={onClose}
      header={header}
      snapPoints={LOGS_SNAP_POINTS}
      testID="phone-linux-logs-sheet"
    >
      <View style={styles.sheetBody}>
        <SegmentedControl options={options} value={kind} onValueChange={onKindChange} size="sm" />
        <Text style={settingsStyles.rowHint} selectable>
          {logsQuery.data?.path ?? " "}
        </Text>
        <Text style={styles.logOutput} selectable dataSet={CODE_SURFACE_DATASET}>
          {contents}
        </Text>
        <View style={styles.sheetActions}>
          <Button variant="outline" size="sm" onPress={handleCopy} disabled={!logsQuery.data}>
            {t("common.actions.copy")}
          </Button>
        </View>
      </View>
    </AdaptiveModalSheet>
  );
}

const styles = StyleSheet.create((theme) => ({
  trailing: {
    flexDirection: "row",
    alignItems: "center",
    gap: theme.spacing[2],
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: theme.borderRadius.full,
  },
  sheetBody: {
    gap: theme.spacing[3],
    paddingBottom: theme.spacing[2],
  },
  logOutput: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.code,
    fontFamily: theme.fontFamily.mono,
    lineHeight: 18,
  },
  sheetActions: {
    flexDirection: "row",
    justifyContent: "flex-end",
  },
}));

const dotStyles = StyleSheet.create((theme) => ({
  success: { backgroundColor: theme.colors.statusDotSuccess },
  warning: { backgroundColor: theme.colors.statusDotWarning },
  error: { backgroundColor: theme.colors.statusDotDanger },
  muted: { backgroundColor: theme.colors.foregroundMuted },
}));
