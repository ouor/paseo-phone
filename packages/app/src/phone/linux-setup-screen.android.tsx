import * as Clipboard from "expo-clipboard";
import { Check, Circle, Copy, FileText, Play, RotateCw, X } from "lucide-react-native";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Alert as NativeAlert, ScrollView, Text, View } from "react-native";
import { StyleSheet, withUnistyles } from "react-native-unistyles";
import { PaseoLogo } from "@/components/icons/paseo-logo";
import { SettingsCard } from "@/components/settings";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { LoadingSpinner } from "@/components/ui/loading-spinner";
import { useToast } from "@/contexts/toast-api-context";
import { useFetchQuery } from "@/data/query";
import { StartupSplashScreen } from "@/screens/startup-splash-screen";
import { CODE_SURFACE_DATASET } from "@/styles/code-surface";
import type { Theme } from "@/styles/theme";
import { UsageMeter } from "@/usage/meter";
import {
  getLogs,
  restartLinux,
  retrySetup,
  start,
  startPrevious,
  type LogKind,
  type PhoneRuntimeState,
  type SetupProgress,
  type SetupStep,
} from "../../modules/paseo-phone-runtime";
import { formatMegabytes } from "./format";
import { PhoneLogsSheet } from "./logs-sheet.android";

type SetupState = Extract<PhoneRuntimeState, { status: "setup" | "setup-failed" }>;
type SetupFailedState = Extract<SetupState, { status: "setup-failed" }>;
export type LinuxSetupState = Extract<
  PhoneRuntimeState,
  { status: "checking" | "setup" | "setup-failed" }
>;
type TFn = ReturnType<typeof useTranslation>["t"];

const STEPS: readonly SetupStep[] = [
  "download",
  "extract",
  "configure",
  "packages",
  "paseo",
  "start",
];
const UPDATE_FIRST_STEP = STEPS.indexOf("paseo");
// `checking` lasts a few hundred ms of disk reads; past this, offer a way out (architecture §7.3).
const CHECKING_ESCAPE_MS = 5_000;

type StepStatus = "done" | "active" | "failed" | "pending";

const mutedIcon = (theme: Theme) => ({
  color: theme.colors.foregroundMuted,
  size: theme.iconSize.sm,
});
const ThemedDone = withUnistyles(Check, (theme) => ({
  color: theme.colors.statusSuccess,
  size: theme.iconSize.sm,
}));
const ThemedFailed = withUnistyles(X, (theme) => ({
  color: theme.colors.statusDanger,
  size: theme.iconSize.sm,
}));
const ThemedPending = withUnistyles(Circle, mutedIcon);
const ThemedSpinner = withUnistyles(LoadingSpinner);
const spinnerColor = (theme: Theme) => ({ color: theme.colors.foregroundMuted });

function reportFailure(title: string) {
  return (error: Error) => NativeAlert.alert(title, error.message);
}

/** Covers app start while the runtime checks, installs, updates, or failed to install Linux. */
export function LinuxSetupScreen({ state }: { state: LinuxSetupState }) {
  if (state.status === "checking") {
    return <CheckingScreen />;
  }
  return <SetupProgressScreen state={state} />;
}

/** Logo, title and description over the screen's content. Same frame for every setup state. */
function SetupFrame({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <View style={styles.screen} testID="phone-linux-setup">
      <ScrollView contentContainerStyle={styles.scrollContent}>
        <View style={styles.content}>
          <View style={styles.header}>
            <PaseoLogo size={64} />
            <Text style={styles.title}>{title}</Text>
            <Text style={styles.description}>{description}</Text>
          </View>
          {children}
        </View>
      </ScrollView>
    </View>
  );
}

function CheckingScreen() {
  const { t } = useTranslation();
  const [slow, setSlow] = useState(false);
  const [logsKind, setLogsKind] = useState<LogKind>("setup");
  const [logsOpen, setLogsOpen] = useState(false);

  useEffect(() => {
    const handle = setTimeout(() => setSlow(true), CHECKING_ESCAPE_MS);
    return () => clearTimeout(handle);
  }, []);

  const handleStart = useCallback(() => {
    start().catch(reportFailure(t("settings.host.linux.startFailed")));
  }, [t]);
  const handleOpenLogs = useCallback(() => setLogsOpen(true), []);
  const handleCloseLogs = useCallback(() => setLogsOpen(false), []);

  if (!slow) {
    return <StartupSplashScreen />;
  }
  return (
    <SetupFrame
      title={t("linuxSetup.checkingSlow.title")}
      description={t("linuxSetup.checkingSlow.description")}
    >
      <View style={styles.actionRow}>
        <Button variant="secondary" leftIcon={FileText} onPress={handleOpenLogs}>
          {t("linuxSetup.checkingSlow.logs")}
        </Button>
        <Button variant="default" leftIcon={Play} onPress={handleStart}>
          {t("linuxSetup.checkingSlow.start")}
        </Button>
      </View>
      <PhoneLogsSheet
        visible={logsOpen}
        kind={logsKind}
        onKindChange={setLogsKind}
        onClose={handleCloseLogs}
      />
    </SetupFrame>
  );
}

function failureCopy(state: SetupFailedState, t: TFn): { title: string; description: string } {
  const step = t(`linuxSetup.steps.${state.step}`);
  if (state.code === "cancelled") {
    return {
      title: t("linuxSetup.cancelledTitle"),
      description: t("linuxSetup.cancelledDescription", { step }),
    };
  }
  const description = state.space
    ? t("linuxSetup.noSpace", {
        free: formatMegabytes(state.space.freeBytes),
        needed: formatMegabytes(state.space.neededBytes),
      })
    : t("linuxSetup.failedDescription", { step });
  return { title: t("linuxSetup.failedTitle"), description };
}

function SetupProgressScreen({ state }: { state: SetupState }) {
  const { t } = useTranslation();
  const failed = state.status === "setup-failed";
  const stepIndex = STEPS.indexOf(state.step);
  // An update starts at `paseo`, or at `download` when the Node pin changed. Keep the first step
  // seen so finished rows stay instead of the card shrinking under the user.
  const [firstStep, setFirstStep] = useState(() =>
    state.mode === "install" ? 0 : Math.min(stepIndex, UPDATE_FIRST_STEP),
  );
  if (stepIndex < firstStep) setFirstStep(stepIndex);

  let title = t(state.mode === "install" ? "linuxSetup.installTitle" : "linuxSetup.updateTitle");
  let description =
    state.status === "setup" && state.mode === "update"
      ? t("linuxSetup.updateDescription", { version: state.targetVersion })
      : t("linuxSetup.installDescription");
  if (state.status === "setup-failed") {
    ({ title, description } = failureCopy(state, t));
  }

  return (
    <SetupFrame title={title} description={description}>
      <SettingsCard testID="phone-linux-setup-steps">
        {STEPS.slice(firstStep).map((step, offset) => {
          const index = firstStep + offset;
          let status: StepStatus = "pending";
          if (index < stepIndex) status = "done";
          else if (index === stepIndex) status = failed ? "failed" : "active";
          return (
            <SetupStepRow
              key={step}
              label={t(`linuxSetup.steps.${step}`)}
              status={status}
              progress={status === "active" && state.status === "setup" ? state.progress : null}
            />
          );
        })}
      </SettingsCard>

      {state.status === "setup-failed" ? (
        <SetupFailure state={state} />
      ) : (
        <Text style={styles.hint}>{t("linuxSetup.backgroundHint")}</Text>
      )}
    </SetupFrame>
  );
}

function SetupStepRow({
  label,
  status,
  progress,
}: {
  label: string;
  status: StepStatus;
  progress: SetupProgress | null;
}) {
  const { t } = useTranslation();
  let value: string | null = null;
  if (progress && progress.total > 0) {
    value =
      progress.unit === "bytes"
        ? t("linuxSetup.progress.bytes", {
            done: formatMegabytes(progress.done),
            total: formatMegabytes(progress.total),
          })
        : t("linuxSetup.progress.count", { done: progress.done, total: progress.total });
  }
  const percent = progress && progress.total > 0 ? (progress.done / progress.total) * 100 : 0;

  return (
    <View style={styles.stepRow}>
      <View style={styles.stepIcon}>
        {status === "done" ? <ThemedDone /> : null}
        {status === "failed" ? <ThemedFailed /> : null}
        {status === "pending" ? <ThemedPending /> : null}
        {status === "active" ? <ThemedSpinner size={14} uniProps={spinnerColor} /> : null}
      </View>
      <View style={styles.stepBody}>
        <View style={styles.stepLine}>
          <Text style={styles.stepLabel}>{label}</Text>
          {value ? <Text style={styles.stepValue}>{value}</Text> : null}
        </View>
        {/* The bar slot is reserved on every row so a step gaining progress does not move the card. */}
        <View style={styles.stepTrack}>
          {progress ? <UsageMeter percent={percent} tone="default" /> : null}
        </View>
      </View>
    </View>
  );
}

function usePhoneLogText(kind: LogKind, refreshKey: string) {
  const { t } = useTranslation();
  const query = useFetchQuery({
    queryKey: ["phone-runtime-logs", kind, refreshKey],
    queryFn: () => getLogs(kind),
    dataShape: "value",
    staleTimeMs: 0,
    retry: false,
  });
  let text = t("linuxSetup.logs.empty");
  if (query.isPending) text = t("linuxSetup.logs.loading");
  else if (query.error) text = t("linuxSetup.logs.loadFailed", { message: query.error.message });
  else if (query.data?.contents) text = query.data.contents;
  return { logs: query.data, text };
}

/** Error message, log tail and actions. Shared by setup failure and the start-failed screen. */
function FailureDetails({
  message,
  logKind,
  offline,
  children,
}: {
  message: string;
  logKind: LogKind;
  offline: boolean;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const toast = useToast();
  const { logs, text } = usePhoneLogText(logKind, message);

  const handleCopyLogs = useCallback(() => {
    const payload = logs ? `${logs.path}\n\n${message}\n\n${logs.contents}` : message;
    void Clipboard.setStringAsync(payload).then(() => toast.copied());
  }, [logs, message, toast]);

  return (
    <>
      {offline ? <Alert variant="warning" size="sm" title={t("linuxSetup.offline")} /> : null}
      <Text dataSet={CODE_SURFACE_DATASET} selectable style={styles.errorMessage}>
        {message}
      </Text>
      <View style={styles.logsContainer}>
        <ScrollView contentContainerStyle={styles.logsContent}>
          <Text dataSet={CODE_SURFACE_DATASET} selectable style={styles.logsText}>
            {text}
          </Text>
        </ScrollView>
      </View>
      <View style={styles.actionRow}>
        <Button variant="secondary" leftIcon={Copy} onPress={handleCopyLogs}>
          {t("linuxSetup.actions.copyLogs")}
        </Button>
        {children}
      </View>
    </>
  );
}

function SetupFailure({ state }: { state: SetupFailedState }) {
  const { t } = useTranslation();
  const handleRetry = useCallback(() => {
    retrySetup().catch(reportFailure(t("linuxSetup.failedTitle")));
  }, [t]);
  const handleStartPrevious = useCallback(() => {
    startPrevious().catch(reportFailure(t("linuxSetup.failedTitle")));
  }, [t]);

  return (
    <FailureDetails message={state.message} logKind="setup" offline={state.code === "offline"}>
      {state.canStartPrevious ? (
        <Button variant="outline" onPress={handleStartPrevious}>
          {t("linuxSetup.actions.startPrevious")}
        </Button>
      ) : null}
      <Button variant="default" leftIcon={RotateCw} onPress={handleRetry}>
        {t("linuxSetup.actions.retry")}
      </Button>
    </FailureDetails>
  );
}

/**
 * Linux is installed but failed to start before this phone's host was registered. Without this
 * screen the app falls through to Welcome with no way back to the Linux section (architecture §7.3).
 */
export function LinuxStartFailedScreen({
  message,
  onContinue,
}: {
  message: string;
  onContinue: () => void;
}) {
  const { t } = useTranslation();
  const handleRestart = useCallback(() => {
    restartLinux().catch(reportFailure(t("linuxSetup.startFailed.title")));
  }, [t]);

  return (
    <SetupFrame
      title={t("linuxSetup.startFailed.title")}
      description={t("linuxSetup.startFailed.description")}
    >
      <FailureDetails message={message} logKind="daemon" offline={false}>
        <Button variant="outline" onPress={onContinue}>
          {t("linuxSetup.startFailed.continue")}
        </Button>
        <Button variant="default" leftIcon={RotateCw} onPress={handleRestart}>
          {t("linuxSetup.startFailed.restart")}
        </Button>
      </FailureDetails>
    </SetupFrame>
  );
}

// Same frame as the StartupSplashScreen error layout (its styles are private to that file).
const styles = StyleSheet.create((theme) => ({
  screen: {
    flex: 1,
    backgroundColor: theme.colors.surface0,
  },
  scrollContent: {
    flexGrow: 1,
    alignItems: "center",
    paddingHorizontal: theme.spacing[6],
    paddingVertical: theme.spacing[8],
    paddingTop: theme.spacing[16],
  },
  content: {
    maxWidth: 720,
    width: "100%",
    gap: theme.spacing[6],
  },
  header: {
    alignItems: "flex-start",
    gap: theme.spacing[2],
  },
  title: {
    color: theme.colors.foreground,
    fontSize: theme.fontSize.base,
    fontWeight: theme.fontWeight.semibold,
  },
  description: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.base,
    lineHeight: 22,
  },
  hint: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.sm,
  },
  stepRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: theme.spacing[3],
    paddingHorizontal: theme.spacing[4],
    paddingTop: theme.spacing[3],
    paddingBottom: theme.spacing[2],
  },
  stepIcon: {
    width: theme.iconSize.sm,
    alignItems: "center",
    marginBottom: theme.spacing[2],
  },
  stepBody: {
    flex: 1,
    gap: theme.spacing[1],
  },
  stepLine: {
    flexDirection: "row",
    justifyContent: "space-between",
    gap: theme.spacing[3],
  },
  stepLabel: {
    color: theme.colors.foreground,
    fontSize: theme.fontSize.base,
  },
  stepValue: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.sm,
    fontVariant: ["tabular-nums"],
  },
  stepTrack: {
    height: 4,
  },
  errorMessage: {
    color: theme.colors.destructive,
    fontSize: theme.fontSize.code,
    lineHeight: 20,
    fontFamily: theme.fontFamily.mono,
  },
  logsContainer: {
    height: 200,
    borderRadius: theme.borderRadius.xl,
    backgroundColor: theme.colors.surface1,
    borderWidth: theme.borderWidth[1],
    borderColor: theme.colors.border,
    overflow: "hidden",
  },
  logsContent: {
    padding: theme.spacing[4],
  },
  logsText: {
    fontFamily: theme.fontFamily.mono,
    fontSize: theme.fontSize.code,
    color: theme.colors.foreground,
    lineHeight: 18,
  },
  actionRow: {
    flexDirection: "row",
    gap: theme.spacing[3],
    flexWrap: "wrap",
  },
}));
