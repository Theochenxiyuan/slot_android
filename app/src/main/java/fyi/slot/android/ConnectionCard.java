package fyi.slot.android;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The connection page's read-only card: QR code beside the address and pairing code. */
final class ConnectionCard {
    private ConnectionCard() { }

    static View create(Context context, HomeLayout layout, AppLanguage language, String url, String pin) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int padding = Math.round(layout.px(14));
        card.setPadding(padding, Math.round(layout.px(6)), padding, Math.round(layout.px(12)));
        String link = url + "/?pin=" + pin;
        int size = Math.round(layout.px(128, 112));
        ImageView code = new ImageView(context);
        BitmapDrawable image = new BitmapDrawable(context.getResources(), QrBitmap.create(link, size));
        image.setFilterBitmap(false);
        code.setImageDrawable(image);
        code.setScaleType(ImageView.ScaleType.FIT_CENTER);
        code.setContentDescription(language.text(R.string.connection_qr, url));
        card.addView(code, new LinearLayout.LayoutParams(size, size));
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = Math.round(layout.px(18));
        card.addView(text, textParams);
        text.addView(caption(context, layout, language.text(R.string.connection_address)));
        text.addView(value(context, layout, url.replaceFirst("^http://", ""), 18, Typeface.DEFAULT_BOLD), spaced(layout, 4));
        text.addView(caption(context, layout, language.text(R.string.connection_pin)), spaced(layout, 14));
        TextView pinView = value(context, layout, pin, 26, Typeface.MONOSPACE);
        pinView.setLetterSpacing(.12f);
        pinView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.addView(pinView, spaced(layout, 4));
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        card.setContentDescription(language.text(R.string.connection_address) + " " + url + ", "
                + language.text(R.string.connection_pin) + " " + String.join(" ", pin.split("")));
        return card;
    }

    private static TextView caption(Context context, HomeLayout layout, String label) {
        TextView view = new TextView(context);
        view.setText(label);
        PanelStyle.text(view, layout, 14, 12, PanelStyle.MUTED);
        return view;
    }

    private static TextView value(Context context, HomeLayout layout, String label, float units, Typeface typeface) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setSingleLine(true);
        view.setTypeface(typeface);
        PanelStyle.text(view, layout, units, 14, PanelStyle.INK);
        return view;
    }

    private static LinearLayout.LayoutParams spaced(HomeLayout layout, float top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(layout.px(top));
        return params;
    }
}
