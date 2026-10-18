package fyi.slot.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import java.util.Locale;

/** Localized UI resources shared by Canvas and native views, without rebuilding the desktop. */
final class AppLanguage {
    enum Choice { SYSTEM, CHINESE, ENGLISH }

    private final Context context;
    private final SharedPreferences storage;
    private Choice choice;
    private Resources resources;
    private Locale locale;

    AppLanguage(Context context) {
        this.context = context.getApplicationContext();
        storage = this.context.getSharedPreferences("language", Context.MODE_PRIVATE);
        choice = Choice.valueOf(storage.getString("choice", Choice.SYSTEM.name()));
        refresh();
    }

    Choice choice() { return choice; }
    Locale locale() { return locale; }
    String text(int id, Object... arguments) { return resources.getString(id, arguments); }

    String choiceLabel() {
        return text(switch (choice) {
            case SYSTEM -> R.string.language_system;
            case CHINESE -> R.string.language_chinese;
            case ENGLISH -> R.string.language_english;
        });
    }

    void cycle(int direction) {
        Choice[] choices = Choice.values();
        select(choices[Math.floorMod(choice.ordinal() + direction, choices.length)]);
    }

    void select(Choice next) {
        choice = next;
        storage.edit().putString("choice", next.name()).apply();
        refresh();
    }

    boolean refresh() { return refresh(context.getResources().getConfiguration()); }

    boolean refresh(Configuration system) {
        Locale next = resolve(choice, system.getLocales());
        boolean changed = !next.equals(locale);
        locale = next;
        Configuration localized = new Configuration(system);
        localized.setLocales(new LocaleList(next));
        localized.setLayoutDirection(next);
        resources = context.createConfigurationContext(localized).getResources();
        return changed;
    }

    static Locale resolve(Choice choice, LocaleList system) {
        if (choice == Choice.CHINESE) return Locale.SIMPLIFIED_CHINESE;
        // A language-only English override resolves back to Chinese on the Y700's system.
        // Use a complete supported locale for both explicit English and the fallback.
        if (choice == Choice.ENGLISH) return Locale.US;
        return !system.isEmpty() && "zh".equals(system.get(0).getLanguage())
                ? Locale.SIMPLIFIED_CHINESE : Locale.US;
    }
}
