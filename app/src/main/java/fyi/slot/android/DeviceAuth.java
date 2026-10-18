package fyi.slot.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Pairing code for one server run; repeated failures pause every check for a while. */
final class DeviceAuth {
    enum Result { OK, DENIED, LOCKED }
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 6, MAX_FAILURES = 8;
    static final long LOCK_MILLIS = 60_000;
    private final byte[] pin;
    private final LongSupplier clock;
    private int failures;
    private long lockedUntil;

    DeviceAuth(String pin, LongSupplier clock) {
        this.pin = pin.getBytes(StandardCharsets.US_ASCII);
        this.clock = clock;
    }

    static String newPin(SecureRandom random) {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return out.toString();
    }

    synchronized Result check(String header) {
        long now = clock.getAsLong();
        if (now < lockedUntil) return Result.LOCKED;
        String given = header == null ? null : header.trim();
        if (given != null && given.regionMatches(true, 0, "Bearer ", 0, 7)) {
            byte[] bytes = given.substring(7).trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(bytes, pin)) { failures = 0; return Result.OK; }
        }
        if (++failures >= MAX_FAILURES) { failures = 0; lockedUntil = now + LOCK_MILLIS; }
        return Result.DENIED;
    }
}
