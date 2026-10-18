package fyi.slot.android;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import java.util.Arrays;
import java.util.Locale;

/** Reports resource resolution on the actual OS without changing its language or stored preferences. */
final class LanguageResourceProbe {
    static String report(Context target) {
        Context app = target.getApplicationContext();
        StringBuilder result = new StringBuilder("System locales: ")
                .append(Resources.getSystem().getConfiguration().getLocales().toLanguageTags()).append('\n');
        result.append("Application locales: ").append(app.getResources().getConfiguration().getLocales().toLanguageTags()).append('\n');
        result.append("Asset locales: ").append(Arrays.toString(app.getAssets().getLocales())).append('\n');
        for (Locale locale : new Locale[]{Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.US}) {
            Configuration full = new Configuration(app.getResources().getConfiguration());
            full.setLocales(new LocaleList(locale));
            full.setLayoutDirection(locale);
            describe(result, "Full override " + locale.toLanguageTag(), app.createConfigurationContext(full));
            Configuration minimal = new Configuration();
            minimal.setLocales(new LocaleList(locale));
            minimal.setLayoutDirection(locale);
            describe(result, "Minimal override " + locale.toLanguageTag(), app.createConfigurationContext(minimal));
        }
        Configuration unsupported = new Configuration(app.getResources().getConfiguration());
        unsupported.setLocales(new LocaleList(Locale.JAPAN));
        result.append("Synthetic Japanese configuration: locales=")
                .append(unsupported.getLocales().toLanguageTags()).append('\n');
        unsupported.setLocales(new LocaleList(Locale.US));
        unsupported.setLayoutDirection(Locale.US);
        describe(result, "English fallback after Japanese", app.createConfigurationContext(unsupported));
        return result.toString();
    }

    static String describe(AppLanguage language) {
        try {
            java.lang.reflect.Field field = AppLanguage.class.getDeclaredField("resources");
            field.setAccessible(true);
            Resources resources = (Resources) field.get(language);
            return "choice=" + language.choice() + ", requested=" + language.locale().toLanguageTag()
                    + ", actual=" + resources.getConfiguration().getLocales().toLanguageTags()
                    + ", settings=" + language.text(R.string.settings);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void describe(StringBuilder result, String name, Context context) {
        Resources resources = context.getResources();
        result.append(name).append(": locales=")
                .append(resources.getConfiguration().getLocales().toLanguageTags())
                .append(", settings=").append(resources.getString(R.string.settings)).append('\n');
    }
}
