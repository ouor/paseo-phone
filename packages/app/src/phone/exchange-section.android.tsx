import { useCallback, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert as NativeAlert, Text } from "react-native";
import { StyleSheet } from "react-native-unistyles";
import { SettingsCard, SettingsRow, SettingsSection } from "@/components/settings";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useToast } from "@/contexts/toast-api-context";
import * as Clipboard from "expo-clipboard";
import {
  openExchangeFolder,
  requestStoragePermission,
  type PhoneRuntimeState,
} from "../../modules/paseo-phone-runtime";
import { restartLinux } from "./runtime.android";

type ReadyState = Extract<PhoneRuntimeState, { status: "ready" }>;

const LINUX_PATH_LABEL = "~/exchange";

export function ExchangeSection({ state }: { state: ReadyState }) {
  const { t } = useTranslation();
  const toast = useToast();
  const exchange = state.exchange;
  const [restarting, setRestarting] = useState(false);

  const handleOpen = useCallback(() => {
    openExchangeFolder().catch((error: Error) =>
      NativeAlert.alert(t("settings.host.exchange.openFailed"), error.message),
    );
  }, [t]);
  const handleCopy = useCallback(() => {
    void Clipboard.setStringAsync(exchange.linuxPath).then(() => toast.copied());
  }, [exchange.linuxPath, toast]);
  const handleAllow = useCallback(() => {
    requestStoragePermission().catch((error: Error) =>
      NativeAlert.alert(t("settings.host.exchange.permission.title"), error.message),
    );
  }, [t]);
  const handleRestart = useCallback(() => {
    setRestarting(true);
    restartLinux()
      .catch((error: Error) =>
        NativeAlert.alert(t("settings.host.linux.startFailed"), error.message),
      )
      .finally(() => setRestarting(false));
  }, [t]);

  let alert = null;
  if (exchange.status === "no-permission") {
    alert = (
      <Alert
        variant="warning"
        title={t("settings.host.exchange.permission.title")}
        description={t("settings.host.exchange.permission.description")}
      >
        <Button variant="outline" size="sm" onPress={handleAllow}>
          {t("settings.host.exchange.permission.action")}
        </Button>
      </Alert>
    );
  } else if (exchange.status === "restart-required") {
    alert = (
      <Alert
        variant="info"
        title={t("settings.host.exchange.restartRequired.title")}
        description={t("settings.host.exchange.restartRequired.description")}
      >
        <Button variant="outline" size="sm" onPress={handleRestart} disabled={restarting}>
          {t("settings.host.exchange.restartRequired.action")}
        </Button>
      </Alert>
    );
  } else if (exchange.status === "error") {
    alert = (
      <Alert
        variant="error"
        title={t("settings.host.exchange.mountFailed")}
        description={exchange.message}
      />
    );
  }

  const mounted = exchange.status === "mounted";
  const linuxHint = useMemo(
    () =>
      mounted ? (
        <Text style={styles.code} selectable>
          {LINUX_PATH_LABEL}
        </Text>
      ) : (
        t("settings.host.exchange.linux.notMounted")
      ),
    [mounted, t],
  );

  return (
    <SettingsSection
      title={t("settings.host.exchange.title")}
      info={t("settings.host.exchange.info")}
      testID="phone-exchange-section"
    >
      {alert}
      <SettingsCard>
        <SettingsRow label={t("settings.host.exchange.phone.title")} hint={exchange.phonePathLabel}>
          <Button variant="outline" onPress={handleOpen}>
            {t("settings.host.exchange.phone.open")}
          </Button>
        </SettingsRow>
        <SettingsRow label={t("settings.host.exchange.linux.title")} hint={linuxHint}>
          <Button variant="outline" onPress={handleCopy} disabled={!mounted}>
            {t("settings.host.remoteAccess.copy")}
          </Button>
        </SettingsRow>
      </SettingsCard>
    </SettingsSection>
  );
}

const styles = StyleSheet.create((theme) => ({
  code: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.sm,
    fontFamily: theme.fontFamily.mono,
    marginTop: theme.spacing[1],
  },
}));
