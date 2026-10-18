package fyi.slot.android;

/** Minimal JSON text building for the device server's small responses. */
final class Json {
    private final StringBuilder out = new StringBuilder();
    private boolean first = true;

    private Json() { }

    static Json object() { Json json = new Json(); json.out.append('{'); return json; }

    Json put(String key, String value) { key(key); quote(out, value); return this; }
    Json put(String key, long value) { key(key); out.append(value); return this; }
    Json put(String key, boolean value) { key(key); out.append(value); return this; }
    Json raw(String key, String json) { key(key); out.append(json); return this; }

    String end() { return out.append('}').toString(); }

    private void key(String key) {
        if (!first) out.append(',');
        first = false;
        quote(out, key);
        out.append(':');
    }

    static String error(String code) { return object().put("error", code).end(); }

    static void quote(StringBuilder out, String value) {
        if (value == null) { out.append("null"); return; }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
