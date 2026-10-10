package eg.mts.gsuif.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * One-shot utility to generate a BCrypt hash for a raw password.
 * Run via: mvnw exec:java -Dexec.mainClass="eg.mts.gsuif.util.HashPassword" -Dexec.args="password123"
 * Delete this file after use.
 */
public class HashPassword {
    public static void main(String[] args) {
        String raw = args.length > 0 ? args[0] : "password123";
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        System.out.println(encoder.encode(raw));
    }
}
