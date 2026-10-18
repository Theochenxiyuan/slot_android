package fyi.slot.android;

import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** Decorative action icons share the label's colour and never introduce a focus target. */
final class MenuIcon {
    private MenuIcon() { }

    static void apply(TextView label, int resource, HomeLayout layout) {
        Drawable icon = resource == 0 ? null : label.getContext().getDrawable(resource).mutate();
        if (icon != null) {
            int size = Math.round(layout.px(19, 18));
            icon.setBounds(0, 0, size, size);
            icon.setTint(label.getCurrentTextColor());
        }
        label.setCompoundDrawablesRelative(icon, null, null, null);
        label.setCompoundDrawablePadding(icon == null ? 0 : Math.round(layout.px(10)));
    }
}
