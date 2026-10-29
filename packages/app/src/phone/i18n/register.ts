// Phone-only copy lives here, not in the app's locale files (architecture §7.3). Other locales fall back to English.
import { i18n } from "@/i18n/i18next";
import en from "./en.json";
import ko from "./ko.json";

const BUNDLES: Record<string, object> = { en, ko };

// The backend loads a locale lazily and merges it shallowly over the top-level keys, which would
// drop a bundle added earlier. Adding a bundle before the backend loads would also make i18next skip
// the backend. So deep-merge only into locales already in the store, and again after each load.
function apply(): void {
  if (!i18n.store) return;
  for (const [language, bundle] of Object.entries(BUNDLES)) {
    if (i18n.hasResourceBundle(language, "translation")) {
      i18n.addResourceBundle(language, "translation", bundle, true, true);
    }
  }
}

let registered = false;

export function registerPhoneTranslations(): void {
  if (registered) return;
  registered = true;
  apply();
  i18n.on("initialized", apply);
  i18n.on("loaded", apply);
}
