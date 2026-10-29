import { useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert as NativeAlert } from "react-native";
import { SettingsCard, SettingsRow, SettingsSection } from "@/components/settings";
import { Button } from "@/components/ui/button";
import { confirmDialog } from "@/utils/confirm-dialog";
import { reinstall } from "../../modules/paseo-phone-runtime";
import { ExchangeSection } from "./exchange-section.android";
import { LinuxSection } from "./linux-section.android";
import { RemoteAccessSection } from "./remote-access-section.android";
import { isPhoneRuntime, usePhoneRuntimeState } from "./runtime.android";

/** Linux, Remote access, Exchange folder and Reinstall on the Host page of this phone's own daemon. */
export function PhoneHostSections({ serverId }: { serverId: string }) {
  if (!isPhoneRuntime) {
    return null;
  }
  return <ThisDeviceSections serverId={serverId} />;
}

function ThisDeviceSections({ serverId }: { serverId: string }) {
  const state = usePhoneRuntimeState();
  if (state?.status !== "ready" || state.daemon.serverId !== serverId) {
    return null;
  }
  return (
    <>
      <LinuxSection state={state} serverId={serverId} />
      <RemoteAccessSection state={state} serverId={serverId} />
      <ExchangeSection state={state} />
      <ReinstallSection />
    </>
  );
}

function ReinstallSection() {
  const { t } = useTranslation();
  // Stays set until the state leaves `ready` and this section unmounts; a second tap would be `busy`.
  const [pending, setPending] = useState(false);
  const handleReinstall = useCallback(async () => {
    const confirmed = await confirmDialog({
      title: t("settings.host.linux.reinstall.confirmTitle"),
      message: t("settings.host.linux.reinstall.confirmMessage"),
      confirmLabel: t("settings.host.linux.reinstall.confirm"),
      cancelLabel: t("common.actions.cancel"),
      destructive: true,
    });
    if (!confirmed) return;
    setPending(true);
    // The runtime switches to `setup` and PhoneRuntimeGate puts the setup screen up.
    reinstall().catch((error: Error) => {
      setPending(false);
      NativeAlert.alert(t("settings.host.linux.reinstall.failed"), error.message);
    });
  }, [t]);

  return (
    <SettingsSection title={t("settings.host.linux.reinstall.sectionTitle")}>
      <SettingsCard>
        <SettingsRow
          label={t("settings.host.linux.reinstall.title")}
          hint={t("settings.host.linux.reinstall.hint")}
        >
          <Button variant="outline" onPress={handleReinstall} disabled={pending}>
            {t(
              pending
                ? "settings.host.linux.reinstall.pending"
                : "settings.host.linux.reinstall.action",
            )}
          </Button>
        </SettingsRow>
      </SettingsCard>
    </SettingsSection>
  );
}
