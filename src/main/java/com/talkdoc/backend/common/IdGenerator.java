package com.talkdoc.backend.common;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public final class IdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    /** UUID v4, used for session ids, question ids and answer ids. */
    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** 32 random bytes, base64url without padding. Used for role tokens. */
    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
