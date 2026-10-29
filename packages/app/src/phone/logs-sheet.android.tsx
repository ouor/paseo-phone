import * as Clipboard from "expo-clipboard";
import { useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Text, View, type ListRenderItem } from "react-native";
import { StyleSheet } from "react-native-unistyles";
import { AdaptiveModalSheet, type SheetHeader } from "@/components/adaptive-modal-sheet";
import { Button } from "@/components/ui/button";
import { FlatList } from "@/components/ui/scroll-view";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { useToast } from "@/contexts/toast-api-context";
import { useFetchQuery } from "@/data/query";
import { CODE_SURFACE_DATASET } from "@/styles/code-surface";
import { settingsStyles } from "@/styles/settings";
import { getLogs, type LogKind } from "../../modules/paseo-phone-runtime";

const LOG_KINDS: readonly LogKind[] = ["daemon", "ssh", "setup"];
// One full-height snap: at a lower snap the bottom of the log list sits below the screen.
const LOGS_SNAP_POINTS = ["92%"];

const renderLine: ListRenderItem<string> = ({ item }) => (
  <Text style={styles.logOutput} selectable dataSet={CODE_SURFACE_DATASET}>
    {item}
  </Text>
);
const lineKey = (_line: string, index: number) => String(index);

/** Daemon, SSH and setup logs. Opened from the Linux section and from the setup screen. */
export function PhoneLogsSheet({
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

  // Newest line first in an inverted list, so offset 0 is the end of the log. The sheet holds its
  // scroll at 0 while it opens, which undid scrollToEnd.
  const lines = useMemo(() => contents.split("\n").reverse(), [contents]);

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
      scrollable={false}
      testID="phone-linux-logs-sheet"
    >
      <View style={styles.sheetBody}>
        <SegmentedControl options={options} value={kind} onValueChange={onKindChange} size="sm" />
        <Text style={settingsStyles.rowHint} selectable>
          {logsQuery.data?.path ?? " "}
        </Text>
        <FlatList
          style={styles.list}
          data={lines}
          renderItem={renderLine}
          keyExtractor={lineKey}
          inverted
          nestedScrollEnabled
        />
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
  sheetBody: {
    flex: 1,
    minHeight: 0,
    gap: theme.spacing[3],
    paddingBottom: theme.spacing[2],
  },
  logOutput: {
    color: theme.colors.foregroundMuted,
    fontSize: theme.fontSize.code,
    fontFamily: theme.fontFamily.mono,
    lineHeight: 18,
  },
  // Bounded by the sheet body, so the log scrolls instead of growing the sheet.
  list: {
    flex: 1,
    minHeight: 0,
  },
  sheetActions: {
    flexDirection: "row",
    justifyContent: "flex-end",
  },
}));
