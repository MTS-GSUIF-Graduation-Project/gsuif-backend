package eg.mts.gsuif.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.util.Arrays;

/**
 * One-shot utility to generate a BCrypt hash for a raw password.
 * Delete this file after use.
 */
public class HashPassword {
    public static void main(String[] args) throws IOException {
        char[] password = readPassword();
        try {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            System.out.println(encoder.encode(CharBuffer.wrap(password)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] readPassword() throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("Password: ");
            if (password == null) throw new IOException("No password was provided");
            return password;
        }

        System.err.println("WARNING: input will be echoed");
        System.out.print("Password: ");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String input = reader.readLine();
        if (input == null) throw new IOException("No password was provided");
        char[] password = input.toCharArray();
        input = null;
        return password;
    }
}
