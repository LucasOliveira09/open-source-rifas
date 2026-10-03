package br.com.iracema.rifas.admin;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class AdminPasswordEncoder implements PasswordEncoder {
    private static final String PREFIX = "pbkdf2-sha256$";
    private static final int ITERATIONS = 310_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    @Override
    public String encode(CharSequence rawPassword) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] derived = derive(rawPassword, salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + Base64.getEncoder().withoutPadding().encodeToString(salt)
                + "$" + Base64.getEncoder().withoutPadding().encodeToString(derived);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || !isEncodedPassword(encodedPassword)) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$", -1);
        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        return MessageDigest.isEqual(expected, derive(rawPassword, salt, iterations));
    }

    public static boolean isEncodedPassword(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return false;
        }
        try {
            String[] parts = value.split("\\$", -1);
            if (parts.length != 4) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] derived = Base64.getDecoder().decode(parts[3]);
            return iterations >= 200_000 && iterations <= 1_000_000
                    && salt.length >= 16 && salt.length <= 64 && derived.length == KEY_LENGTH_BITS / 8;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(CharSequence rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toString().toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Não foi possível validar a senha do administrador.", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
