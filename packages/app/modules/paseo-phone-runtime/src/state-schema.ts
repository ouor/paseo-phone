// Native core <-> UI state contract (architecture §7.1). This schema is the TS source of truth;
// Kotlin's StateSnapshot.kt is held to it by the golden fixtures in __fixtures__/.
import { z } from "zod";

export const setupStepSchema = z.enum([
  "download",
  "extract",
  "configure",
  "packages",
  "paseo",
  "start",
]);

export const setupErrorCodeSchema = z.enum([
  "offline",
  "no-space",
  "checksum",
  "command-failed",
  "cancelled",
  "unknown",
]);

export const setupProgressSchema = z.object({
  done: z.number(),
  total: z.number(), // -1 when unknown
  unit: z.enum(["bytes", "packages"]),
});

export const serviceErrorCodeSchema = z.enum([
  "crashed",
  "killed-by-system", // proot ended with a SIGKILL we did not send (phantom process killer, low memory)
  "not-responding",
  "start-failed",
  "restart-limit",
]);

export const serviceStateSchema = z.discriminatedUnion("state", [
  z.object({ state: z.literal("starting") }),
  z.object({ state: z.literal("running") }),
  z.object({ state: z.literal("stopping") }),
  z.object({ state: z.literal("stopped") }),
  z.object({ state: z.literal("error"), code: serviceErrorCodeSchema, message: z.string() }),
]);

export const networkAddressSchema = z.object({
  kind: z.enum(["wifi", "tailscale"]),
  address: z.string(), // IPv4
});

export const exchangeStatusSchema = z.discriminatedUnion("status", [
  z.object({ status: z.literal("mounted") }),
  z.object({ status: z.literal("no-permission") }),
  z.object({ status: z.literal("restart-required") }),
  z.object({ status: z.literal("error"), message: z.string() }),
]);

export const deviceChecksSchema = z.object({
  batteryOptimized: z.boolean(), // true = subject to battery optimization (warn)
  phantomProcessMonitor: z.enum(["disabled", "enabled", "unknown"]),
  notificationsAllowed: z.boolean(),
});

const modeSchema = z.enum(["install", "update"]);

const exchangeLocationSchema = z.object({
  phonePathLabel: z.string(),
  phonePath: z.string(),
  linuxPath: z.string(),
});

export const phoneRuntimeStateSchema = z.discriminatedUnion("status", [
  z.object({ status: z.literal("checking") }),
  z.object({
    status: z.literal("setup"),
    mode: modeSchema,
    step: setupStepSchema,
    progress: setupProgressSchema.nullable(),
    targetVersion: z.string(), // Paseo daemon version being installed
  }),
  z.object({
    status: z.literal("setup-failed"),
    mode: modeSchema,
    step: setupStepSchema,
    code: setupErrorCodeSchema,
    message: z.string(), // raw, not translated; the UI does not parse it
    space: z.object({ freeBytes: z.number(), neededBytes: z.number() }).optional(), // code === "no-space"
    canStartPrevious: z.boolean(),
  }),
  z.object({
    status: z.literal("ready"),
    linux: serviceStateSchema, // overall; when stopped/error, daemon.state and ssh.state carry no meaning
    daemon: serviceStateSchema.and(
      z.object({ serverId: z.string().nullable(), version: z.string(), listen: z.string() }),
    ),
    ssh: z.object({
      enabled: z.boolean(),
      port: z.number(),
      state: serviceStateSchema,
      hostKeyFingerprints: z.array(z.string()), // "ssh-ed25519 SHA256:…"
    }),
    keepAwake: z.boolean(),
    distro: z.string(),
    addresses: z.array(networkAddressSchema),
    exchange: exchangeStatusSchema.and(exchangeLocationSchema),
    device: deviceChecksSchema,
  }),
]);
