import { describe, expect, it } from "vitest";
import {
  buildDesktopSshUri,
  buildSshCommand,
  buildSshConfig,
  formatMegabytes,
  pickAddress,
  readPhoneNotificationIntent,
  toSshHostAlias,
} from "./format";

describe("phone remote access strings", () => {
  it("builds the ssh command, desktop uri and config", () => {
    expect(buildSshCommand("192.168.1.23", 2222)).toBe("ssh -p 2222 root@192.168.1.23");
    expect(buildDesktopSshUri("100.64.0.3", 2222)).toBe("ssh://root@100.64.0.3:2222");
    expect(buildSshConfig("pixel-8", "192.168.1.23", 2222)).toBe(
      "Host pixel-8\n  HostName 192.168.1.23\n  Port 2222\n  User root",
    );
  });

  it("turns a host label into an ssh alias", () => {
    expect(toSshHostAlias("Pixel 8")).toBe("pixel-8");
    expect(toSshHostAlias("  POCO F3 (dev)  ")).toBe("poco-f3-dev");
    expect(toSshHostAlias("내 폰")).toBe("paseo-phone");
  });

  it("picks the preferred address, then wifi, then the first", () => {
    const wifi = { kind: "wifi", address: "192.168.1.23" } as const;
    const tailscale = { kind: "tailscale", address: "100.64.0.3" } as const;
    expect(pickAddress([tailscale, wifi], "tailscale")).toBe(tailscale);
    expect(pickAddress([tailscale, wifi], null)).toBe(wifi);
    expect(pickAddress([tailscale], "wifi")).toBe(tailscale);
    expect(pickAddress([], "wifi")).toBeNull();
  });
});

describe("phone setup strings", () => {
  it("formats download sizes", () => {
    expect(formatMegabytes(74_400_000)).toBe("74 MB");
    expect(formatMegabytes(2_100_000_000)).toBe("2.1 GB");
  });
});

describe("readPhoneNotificationIntent", () => {
  it("reads confirmStop and logs from the deep-link query", () => {
    expect(readPhoneNotificationIntent("1", undefined)).toEqual({
      confirmStop: true,
      logs: null,
    });
    expect(readPhoneNotificationIntent(undefined, "daemon")).toEqual({
      confirmStop: false,
      logs: "daemon",
    });
    expect(readPhoneNotificationIntent(["1"], "bogus")).toEqual({
      confirmStop: true,
      logs: null,
    });
    expect(readPhoneNotificationIntent(undefined, undefined)).toEqual({
      confirmStop: false,
      logs: null,
    });
  });
});
