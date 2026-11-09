package com.infinitetoken.lumber;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-256-GCM in Lumber's layout, with the JDK's own crypto. */
final class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    /** Seals plaintext under a nonce: the 16-byte auth tag, then the ciphertext. Java puts the tag last. */
    static byte[] seal(SecretKey key, byte[] nonce, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(Contract.TAG_BYTES * 8, nonce));
        byte[] sealed = cipher.doFinal(plaintext);
        int split = sealed.length - Contract.TAG_BYTES;
        byte[] out = new byte[sealed.length];
        System.arraycopy(sealed, split, out, 0, Contract.TAG_BYTES);
        System.arraycopy(sealed, 0, out, Contract.TAG_BYTES, split);
        return out;
    }

    /** Encrypts a payload under a fresh random nonce, as POST /logs' {@code encrypted}. Never reuse a nonce. */
    static Map<String, Object> encrypt(SecretKey key, byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[Contract.NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Base64.Encoder base64 = Base64.getEncoder();
        Map<String, Object> encrypted = new LinkedHashMap<>();
        encrypted.put("nonce", base64.encodeToString(nonce));
        encrypted.put("payload", base64.encodeToString(seal(key, nonce, plaintext)));
        encrypted.put("keyVersion", Contract.KEY_VERSION);
        return encrypted;
    }
}
