import * as Clipboard from "expo-clipboard";
import { useCallback, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Text, View } from "react-native";
import { StyleSheet } from "react-native-unistyles";
import {
  SettingsCard,
  SettingsCollapsibleRow,
  SettingsDetailRow,
  SettingsRow,
  SettingsSection,
} from "@/components/settings";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ScrollableCodeSurface } from "@/components/ui/scrollable-code-surface";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { useToast } from "@/contexts/toast-api-context";
import { useHosts } from "@/runtime/host-runtime";
import type { NetworkAddress, PhoneRuntimeState } from "../../modules/paseo-phone-runtime";
import { AuthorizedKeysSheet, useAuthorizedKeys } from "./authorized-keys-sheet.android";
import {
  buildDesktopSshUri,
  buildSshCommand,
  buildSshConfig,
  pickAddress,
  toSshHostAlias,
} from "./format";

type ReadyState = Extract<PhoneRuntimeState, { status: "ready" }>;
type AddressKind = NetworkAddress["kind"];

function CopyButton({ text, disabled }: { text: string; disabled: boolean }) {
  const { t } = useTranslation();
  const toast = useToast();
  const handleCopy = useCallback(() => {
    void Clipboard.setStringAsync(text).then(() => toast.copied());
  }, [text, toast]);
  return (
    <Button variant="outline" onPress={handleCopy} disabled={disabled}>
      {t("settings.host.remoteAccess.copy")}
    </Button>
  );
}

/** A row whose hint is a command, path or key in the code font, with an optional line under it. */
function CodeRow({
  label,
  code,
  sub,
  copyDisabled,
}: {
  label: string;
  code: string;
  sub?: string;
  /** Omit for a row without a Copy button. */
  copyDisabled?: boolean;
}) {
  const hint = useMemo(
    () => (
      <View>
        <Text style={styles.code} selectable>
          {code}
        </Text>
        {sub ? <Text style={styles.subHint}>{sub}</Text> : null}
      </View>
    ),
    [code, sub],
  );
  return (
    <SettingsRow label={label} hint={hint}>
      {copyDisabled === undefined ? null : <CopyButton text={code} disabled={copyDisabled} />}
    </SettingsRow>
  );
}

function SshConfigRow({
  label,
  config,
  disabled,
}: {
  label: string;
  config: string;
  disabled: boolean;
}) {
  const hint = useMemo(
    () => (
      <ScrollableCodeSurface horizontal selectable style={styles.configSurface}>
        {config}
      </ScrollableCodeSurface>
    ),
    [config],
  );
  return (
    <SettingsRow label={label} hint={hint}>
      <CopyButton text={config} disabled={disabled} />
    </SettingsRow>
  );
}

/** One alert per region (docs/design.md §11): the first thing blocking a desktop from connecting. */
function RemoteAccessAlert({
  hasAddress,
  sshUp,
  noKeys,
  onAddKey,
}: {
  hasAddress: boolean;
  sshUp: boolean;
  noKeys: boolean;
  onAddKey: () => void;
}) {
  const { t } = useTranslation();
  if (!hasAddress) {
    return (
      <Alert
        variant="warning"
        title={t("settings.host.remoteAccess.network.noneTitle")}
        description={t("settings.host.remoteAccess.network.noneDescription")}
      />
    );
  }
  if (!sshUp) {
    return (
      <Alert
        variant="default"
        title={t("settings.host.remoteAccess.sshOff.title")}
        description={t("settings.host.remoteAccess.sshOff.description")}
      />
    );
  }
  if (noKeys) {
    // Key-only sshd: nothing below works until a desktop key is added, so say it first.
    return (
      <Alert
        variant="info"
        title={t("settings.host.remoteAccess.noKeys.title")}
        description={t("settings.host.remoteAccess.noKeys.description")}
      >
        <Button variant="outline" size="sm" onPress={onAddKey}>
          {t("settings.host.remoteAccess.keys.add")}
        </Button>
      </Alert>
    );
  }
  return null;
}

export function RemoteAccessSection({ state, serverId }: { state: ReadyState; serverId: string }) {
  const { t } = useTranslation();
  const hostLabel = useHosts().find((host) => host.serverId === serverId)?.label ?? "";
  const [preferredKind, setPreferredKind] = useState<AddressKind | null>(null);
  const [keysSheet, setKeysSheet] = useState({ open: false, generation: 0 });
  const keysQuery = useAuthorizedKeys();

  const address = pickAddress(state.addresses, preferredKind);
  const kinds = useMemo(
    () => Array.from(new Set(state.addresses.map((entry) => entry.kind))),
    [state.addresses],
  );
  const kindOptions = useMemo(
    () =>
      kinds.map((kind) => ({
        value: kind,
        label: t(`settings.host.remoteAccess.network.${kind}`),
      })),
    [kinds, t],
  );
  const sshUp = state.ssh.enabled && state.linux.state === "running";
  const copyDisabled = !address || !sshUp;
  const port = state.ssh.port;
  const host = address?.address ?? "";

  const openKeys = useCallback(
    () => setKeysSheet((sheet) => ({ open: true, generation: sheet.generation + 1 })),
    [],
  );
  const closeKeys = useCallback(() => setKeysSheet((sheet) => ({ ...sheet, open: false })), []);

  let networkHint = "—";
  if (address && kinds.length === 1) {
    networkHint = `${t(`settings.host.remoteAccess.network.${address.kind}`)} · ${address.address}`;
  } else if (address) {
    networkHint = address.address;
  }

  let keysSummary = "";
  if (keysQuery.data) {
    keysSummary =
      keysQuery.data.length > 0
        ? t("settings.host.remoteAccess.keys.count", { count: keysQuery.data.length })
        : t("settings.host.remoteAccess.keys.none");
  }

  const sshCommand = address ? buildSshCommand(host, port) : "—";
  const desktopUri = address ? buildDesktopSshUri(host, port) : "—";
  const sshConfig = buildSshConfig(toSshHostAlias(hostLabel), host || "<address>", port);
  // A current OpenSSH client negotiates ed25519 first; listing every key type buried the one to match.
  const hostKeys = state.ssh.hostKeyFingerprints;
  const fingerprints =
    hostKeys.find((entry) => entry.startsWith("ssh-ed25519")) ?? hostKeys[0] ?? "—";

  return (
    <SettingsSection
      title={t("settings.host.remoteAccess.title")}
      info={t("settings.host.remoteAccess.info")}
      testID="phone-remote-access-section"
    >
      <RemoteAccessAlert
        hasAddress={address !== null}
        sshUp={sshUp}
        noKeys={keysQuery.data?.length === 0}
        onAddKey={openKeys}
      />
      <SettingsCard>
        <SettingsRow label={t("settings.host.remoteAccess.network.title")} hint={networkHint}>
          {kinds.length > 1 && address ? (
            <SegmentedControl
              options={kindOptions}
              value={address.kind}
              onValueChange={setPreferredKind}
            />
          ) : null}
        </SettingsRow>
        <CodeRow
          label={t("settings.host.remoteAccess.ssh.title")}
          code={sshCommand}
          copyDisabled={copyDisabled}
        />
        <CodeRow
          label={t("settings.host.remoteAccess.desktop.title")}
          code={desktopUri}
          sub={t("settings.host.remoteAccess.desktop.hint")}
          copyDisabled={copyDisabled}
        />
        <SettingsCollapsibleRow label={t("settings.host.remoteAccess.sshConfig.title")}>
          <SshConfigRow
            label={t("settings.host.remoteAccess.sshConfig.hint")}
            config={sshConfig}
            disabled={copyDisabled}
          />
        </SettingsCollapsibleRow>
        <CodeRow
          label={t("settings.host.remoteAccess.hostKey.title")}
          code={fingerprints}
          sub={t("settings.host.remoteAccess.hostKey.hint")}
        />
        <SettingsDetailRow
          label={t("settings.host.remoteAccess.keys.title")}
          summary={keysSummary}
          onPress={openKeys}
          testID="phone-authorized-keys-row"
        />
      </SettingsCard>
      <AuthorizedKeysSheet
        key={keysSheet.generation}
        visible={keysSheet.open}
        onClose={closeKeys}
      />
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
  subHint: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.sm,
    marginTop: theme.spacing[1],
  },
  configSurface: {
    marginTop: theme.spacing[2],
  },
}));
