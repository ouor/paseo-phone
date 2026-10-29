// Off Android there is no phone runtime. runtime.android.ts is the real implementation.
import type { PhoneRuntimeState } from "../../modules/paseo-phone-runtime";

export const isPhoneRuntime: boolean = false;

export function usePhoneRuntimeState(): PhoneRuntimeState | null {
  return null;
}
