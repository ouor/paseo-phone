import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useCallback, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert as NativeAlert, Text, View } from "react-native";
import { StyleSheet } from "react-native-unistyles";
import { AdaptiveModalSheet, type SheetHeader } from "@/components/adaptive-modal-sheet";
import { SettingsCard, SettingsRow } from "@/components/settings";
import { Button } from "@/components/ui/button";
import { Field, FormTextInput } from "@/components/ui/form-field";
import type { EditingTextInputHandle } from "@/components/ui/text-input";
import { useIsCompactFormFactor } from "@/constants/layout";
import { useFetchQuery } from "@/data/query";
import { confirmDialog } from "@/utils/confirm-dialog";
import {
  addAuthorizedKey,
  listAuthorizedKeys,
  PhoneRuntimeError,
  removeAuthorizedKey,
  type AuthorizedKey,
} from "../../modules/paseo-phone-runtime";

const KEYS_QUERY_KEY = ["phone-authorized-keys"] as const;

export function useAuthorizedKeys() {
  return useFetchQuery({
    queryKey: KEYS_QUERY_KEY,
    queryFn: listAuthorizedKeys,
    dataShape: "list",
    staleTimeMs: 0,
    retry: false,
  });
}

/** Paste a desktop public key into ~/.ssh/authorized_keys inside Linux, or remove one. */
export function AuthorizedKeysSheet({
  visible,
  onClose,
}: {
  visible: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const isCompact = useIsCompactFormFactor();
  const queryClient = useQueryClient();
  const keysQuery = useAuthorizedKeys();
  const inputRef = useRef<EditingTextInputHandle>(null);
  const [text, setText] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const header = useMemo<SheetHeader>(
    () => ({ title: t("settings.host.remoteAccess.keys.title") }),
    [t],
  );

  const addMutation = useMutation({
    mutationFn: (value: string) => addAuthorizedKey(value),
    onSuccess: () => {
      inputRef.current?.replaceText("");
      setText("");
      return queryClient.invalidateQueries({ queryKey: KEYS_QUERY_KEY });
    },
    onError: (error: Error) => {
      const code = error instanceof PhoneRuntimeError ? error.code : null;
      if (code === "private-key") {
        // Never keep a pasted private key on screen or in state.
        inputRef.current?.replaceText("");
        setText("");
        setFieldError(t("settings.host.remoteAccess.keys.privateKey"));
      } else if (code === "invalid-key") {
        setFieldError(t("settings.host.remoteAccess.keys.invalid"));
      } else if (code === "duplicate-key") {
        setFieldError(t("settings.host.remoteAccess.keys.duplicate"));
      } else {
        setFieldError(error.message);
      }
    },
  });

  const handleChange = useCallback(
    (value: string) => {
      // Same test the core uses; catch it before it reaches state or the native side.
      if (value.includes("-----BEGIN") || value.includes("PRIVATE KEY")) {
        inputRef.current?.replaceText("");
        setText("");
        setFieldError(t("settings.host.remoteAccess.keys.privateKey"));
        return;
      }
      setText(value);
      setFieldError(null);
    },
    [t],
  );
  const handleAdd = useCallback(() => {
    const value = text.trim();
    if (value) addMutation.mutate(value);
  }, [addMutation, text]);

  const handleRemove = useCallback(
    async (key: AuthorizedKey) => {
      const confirmed = await confirmDialog({
        title: t("settings.host.remoteAccess.keys.removeTitle"),
        message: t("settings.host.remoteAccess.keys.removeMessage", {
          comment: key.comment || key.fingerprint,
        }),
        confirmLabel: t("settings.host.remoteAccess.keys.remove"),
        cancelLabel: t("common.actions.cancel"),
        destructive: true,
      });
      if (!confirmed) return;
      try {
        await removeAuthorizedKey(key.fingerprint);
      } catch (error) {
        NativeAlert.alert(
          t("settings.host.remoteAccess.keys.removeFailed"),
          error instanceof Error ? error.message : String(error),
        );
      }
      await queryClient.invalidateQueries({ queryKey: KEYS_QUERY_KEY });
    },
    [queryClient, t],
  );

  let list = <Text style={styles.muted}>{t("common.loading")}</Text>;
  if (keysQuery.error) {
    list = (
      <Text style={styles.muted}>
        {t("settings.host.remoteAccess.keys.loadFailed", { message: keysQuery.error.message })}
      </Text>
    );
  } else if (keysQuery.data?.length === 0) {
    list = <Text style={styles.muted}>{t("settings.host.remoteAccess.keys.emptyList")}</Text>;
  } else if (keysQuery.data) {
    list = (
      <SettingsCard>
        {keysQuery.data.map((key) => (
          <KeyRow key={key.fingerprint} authorizedKey={key} onRemove={handleRemove} />
        ))}
      </SettingsCard>
    );
  }

  return (
    <AdaptiveModalSheet
      visible={visible}
      onClose={onClose}
      header={header}
      testID="phone-authorized-keys-sheet"
    >
      <View style={styles.body}>
        {list}
        <Field label={t("settings.host.remoteAccess.keys.fieldLabel")} error={fieldError}>
          <FormTextInput
            ref={inputRef}
            size={isCompact ? "md" : "sm"}
            initialValue=""
            onChangeText={handleChange}
            placeholder="ssh-ed25519 AAAA… user@host"
            accessibilityLabel={t("settings.host.remoteAccess.keys.fieldLabel")}
            autoCapitalize="none"
            autoCorrect={false}
            multiline
            editable={!addMutation.isPending}
            testID="phone-authorized-keys-input"
          />
        </Field>
        <View style={styles.actions}>
          <Button
            variant="default"
            onPress={handleAdd}
            disabled={!text.trim() || addMutation.isPending}
          >
            {t(
              addMutation.isPending
                ? "settings.host.remoteAccess.keys.adding"
                : "settings.host.remoteAccess.keys.add",
            )}
          </Button>
        </View>
      </View>
    </AdaptiveModalSheet>
  );
}

function KeyRow({
  authorizedKey,
  onRemove,
}: {
  authorizedKey: AuthorizedKey;
  onRemove: (key: AuthorizedKey) => void;
}) {
  const { t } = useTranslation();
  const handleRemove = useCallback(() => onRemove(authorizedKey), [authorizedKey, onRemove]);
  const hint = useMemo(
    () => (
      <Text style={styles.fingerprint} selectable>
        {`${authorizedKey.type} ${authorizedKey.fingerprint}`}
      </Text>
    ),
    [authorizedKey.fingerprint, authorizedKey.type],
  );
  return (
    <SettingsRow label={authorizedKey.comment || authorizedKey.type} hint={hint}>
      <Button variant="outline" onPress={handleRemove}>
        {t("settings.host.remoteAccess.keys.remove")}
      </Button>
    </SettingsRow>
  );
}

const styles = StyleSheet.create((theme) => ({
  body: {
    gap: theme.spacing[4],
    paddingBottom: theme.spacing[2],
  },
  muted: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.sm,
  },
  fingerprint: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.sm,
    fontFamily: theme.fontFamily.mono,
    marginTop: theme.spacing[1],
  },
  actions: {
    flexDirection: "row",
    justifyContent: "flex-end",
  },
}));
