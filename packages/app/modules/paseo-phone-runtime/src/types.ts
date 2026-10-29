// Native core <-> UI contract (architecture §7.1). Types come from state-schema.ts; edit the schema, not these.
import type { z } from "zod";
import type {
  deviceChecksSchema,
  exchangeStatusSchema,
  networkAddressSchema,
  phoneRuntimeStateSchema,
  serviceErrorCodeSchema,
  serviceStateSchema,
  setupErrorCodeSchema,
  setupProgressSchema,
  setupStepSchema,
} from "./state-schema";

export type SetupStep = z.infer<typeof setupStepSchema>;
export type SetupErrorCode = z.infer<typeof setupErrorCodeSchema>;
export type SetupProgress = z.infer<typeof setupProgressSchema>;
export type ServiceErrorCode = z.infer<typeof serviceErrorCodeSchema>;
export type ServiceState = z.infer<typeof serviceStateSchema>;
export type NetworkAddress = z.infer<typeof networkAddressSchema>;
export type ExchangeStatus = z.infer<typeof exchangeStatusSchema>;
export type DeviceChecks = z.infer<typeof deviceChecksSchema>;
export type PhoneRuntimeState = z.infer<typeof phoneRuntimeStateSchema>;

export interface AuthorizedKey {
  type: string; // "ssh-ed25519"
  comment: string; // "" when absent
  fingerprint: string; // "SHA256:…"
}

export type LogKind = "daemon" | "ssh" | "setup";

export type PhoneRuntimeErrorCode =
  | "busy"
  | "not-installed"
  | "invalid-key"
  | "private-key"
  | "duplicate-key"
  | "not-found"
  | "io-error"
  | "unavailable"; // called on a build without the native runtime (iOS, web, non-phone variant)

/**
 * Query keys of the notification deep links (architecture §7.4). Kotlin mirrors these in
 * notify/RuntimeNotification.kt; change both together.
 */
export const PHONE_NOTIFICATION_QUERY = { confirmStop: "confirmStop", logs: "logs" } as const;
