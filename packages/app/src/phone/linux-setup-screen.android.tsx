import * as Clipboard from "expo-clipboard";
import { Check, Circle, Copy, RotateCw, X } from "lucide-react-native";
import { useCallback, useState } from "react";
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
  retrySetup,
  startPrevious,
  type PhoneRuntimeState,
  type SetupProgress,
  type SetupStep,
} from "../../modules/paseo-phone-runtime";
import { formatMegabytes, parseNoSpaceMessage } from "./format";

type SetupState = Extract<PhoneRuntimeState, { status: "setup" | "setup-failed" }>;
export type LinuxSetupState = Extract<
  PhoneRuntimeState,
  { status: "checking" | "setup" | "setup-failed" }
>;

const STEPS: readonly SetupStep[] = [
  "download",
  "extract",
  "configure",
  "packages",
  "paseo",
  "start",
];
const UPDATE_FIRST_STEP = STEPS.indexOf("paseo");

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

/** Covers app start while the runtime checks, installs, updates, or failed to install Linux. */
export function LinuxSetupScreen({ state }: { state: LinuxSetupState }) {
  if (state.status === "checking") {
    return <StartupSplashScreen />;
  }
  return <SetupProgressScreen state={state} />;
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

  const stepLabel = t(`linuxSetup.steps.${state.step}`);
  let title = t(state.mode === "install" ? "linuxSetup.installTitle" : "linuxSetup.updateTitle");
  let description =
    state.status === "setup" && state.mode === "update"
      ? t("linuxSetup.updateDescription", { version: state.targetVersion })
      : t("linuxSetup.installDescription");
  if (failed) {
    title = t("linuxSetup.failedTitle");
    const space = state.code === "no-space" ? parseNoSpaceMessage(state.message) : null;
    description = space
      ? t("linuxSetup.noSpace", space)
      : t("linuxSetup.failedDescription", { step: stepLabel });
  }

  return (
    <View style={styles.screen} testID="phone-linux-setup">
      <ScrollView contentContainerStyle={styles.scrollContent}>
        <View style={styles.content}>
          <View style={styles.header}>
            <PaseoLogo size={64} />
            <Text style={styles.title}>{title}</Text>
            <Text style={styles.description}>{description}</Text>
          </View>

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

          {failed ? (
            <SetupFailure state={state} />
          ) : (
            <Text style={styles.hint}>{t("linuxSetup.backgroundHint")}</Text>
          )}
        </View>
      </ScrollView>
    </View>
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

function SetupFailure({ state }: { state: Extract<SetupState, { status: "setup-failed" }> }) {
  const { t } = useTranslation();
  const toast = useToast();
  const logsQuery = useFetchQuery({
    queryKey: ["phone-runtime-logs", "setup", state.step, state.message],
    queryFn: () => getLogs("setup"),
    dataShape: "value",
    staleTimeMs: 0,
    retry: false,
  });

  let logsText = t("startup.logs.unavailable");
  if (logsQuery.isPending) logsText = t("startup.logs.loading");
  else if (logsQuery.data?.contents) logsText = logsQuery.data.contents;

  const handleCopyLogs = useCallback(() => {
    const logs = logsQuery.data;
    const payload = logs ? `${logs.path}\n\n${state.message}\n\n${logs.contents}` : state.message;
    void Clipboard.setStringAsync(payload).then(() => toast.copied());
  }, [logsQuery.data, state.message, toast]);

  const handleRetry = useCallback(() => {
    retrySetup().catch((error: Error) =>
      NativeAlert.alert(t("linuxSetup.failedTitle"), error.message),
    );
  }, [t]);

  const handleStartPrevious = useCallback(() => {
    startPrevious().catch((error: Error) =>
      NativeAlert.alert(t("linuxSetup.failedTitle"), error.message),
    );
  }, [t]);

  return (
    <>
      {state.code === "offline" ? (
        <Alert variant="warning" size="sm" title={t("linuxSetup.offline")} />
      ) : null}
      <Text dataSet={CODE_SURFACE_DATASET} selectable style={styles.errorMessage}>
        {state.message}
      </Text>
      <View style={styles.logsContainer}>
        <ScrollView contentContainerStyle={styles.logsContent}>
          <Text dataSet={CODE_SURFACE_DATASET} selectable style={styles.logsText}>
            {logsText}
          </Text>
        </ScrollView>
      </View>
      <View style={styles.actionRow}>
        <Button variant="secondary" leftIcon={Copy} onPress={handleCopyLogs}>
          {t("linuxSetup.actions.copyLogs")}
        </Button>
        {state.canStartPrevious ? (
          <Button variant="outline" onPress={handleStartPrevious}>
            {t("linuxSetup.actions.startPrevious")}
          </Button>
        ) : null}
        <Button variant="default" leftIcon={RotateCw} onPress={handleRetry}>
          {t("linuxSetup.actions.retry")}
        </Button>
      </View>
    </>
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
