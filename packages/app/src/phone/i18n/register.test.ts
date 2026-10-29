import { afterAll, describe, expect, it } from "vitest";
import { i18n } from "@/i18n/i18next";
import { registerPhoneTranslations } from "./register";

registerPhoneTranslations();

describe("phone i18n bundle", () => {
  afterAll(async () => {
    await i18n.changeLanguage("en");
  });

  it("adds phone keys without dropping app keys, including a lazily loaded locale", async () => {
    expect(i18n.t("linuxSetup.installTitle")).toBe("Setting up Linux");
    expect(i18n.t("settings.host.appearance.title")).toBe("Appearance");

    await i18n.changeLanguage("ko");
    expect(i18n.t("linuxSetup.installTitle")).toBe("Linux 설치 중");
    expect(i18n.t("common.actions.cancel")).not.toBe("common.actions.cancel");
    expect(i18n.t("common.actions.cancel")).not.toBe("Cancel");
    // Sibling of a phone key under settings.host must survive the merge.
    expect(i18n.t("settings.host.appearance.title")).not.toBe("Appearance");
    expect(i18n.t("settings.host.linux.status.running")).toBe("실행 중");
  });
});
