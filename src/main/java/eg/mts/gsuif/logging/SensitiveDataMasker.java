package eg.mts.gsuif.logging;

import java.util.regex.Pattern;

/**
 * Shared utility for masking sensitive values before they reach any log output.
 *
 * <p>Two patterns are applied sequentially:
 * <ol>
 *   <li><b>KV pattern</b> — covers {@code key=value} and {@code key: value} formats
 *       for passwords, tokens, secrets, and Authorization headers.
 *       For {@code authorization}, a second word is optionally consumed to capture
 *       {@code Authorization: Bearer &lt;token&gt;} in one match.
 *   <li><b>Bearer pattern</b> — covers the standalone {@code Bearer &lt;token&gt;}
 *       format (space-separated, no key prefix).
 * </ol>
 *
 * <p>Both patterns are case-insensitive. STD-16.
 */
public final class SensitiveDataMasker {

    /**
     * Matches sensitive keys with optional quotes, preserving key quotes, separator,
     * and value quotes for valid JSON / structured log readability.
     *
     * <p>Groups:
     * <ul>
     *   <li>Group 1: optional key quote ({@code "} or {@code '})
     *   <li>Group 2: key name (password, passwd, token, secret, authorization)
     *   <li>Group 3: separator with surrounding whitespace (e.g. {@code =}, {@code : })
     *   <li>Group 4: value quote ({@code "} or {@code '}, {@code null} if unquoted)
     *   <li>Group 5: unquoted value (stops before {@code ,}, {@code ;}, {@code }}, {@code ]}, {@code )}, or whitespace)
     * </ul>
     */
    private static final Pattern KV_PATTERN = Pattern.compile(
            "(?i)([\"']?)(password|passwd|token|secret|authorization)\\1(\\s*[=:]\\s*)(?:(\"|')(.*?)\\4|((?:bearer\\s+)?[^\\s,;}\\]\\)]+))",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches standalone {@code Bearer <token>} format (space-separated, optional quotes).
     */
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)\\b(bearer\\s+)([\"']?)(\\S+?)\\2(?=[\\s,;}\\]\\)]|$)",
            Pattern.CASE_INSENSITIVE
    );

    private SensitiveDataMasker() {
        // utility class — no instances
    }

    /**
     * Returns {@code input} with all sensitive values replaced by {@code ***MASKED***}.
     * Returns {@code null} unchanged if {@code input} is {@code null}.
     *
     * <p>Preserves surrounding quotes and delimiters so JSON payloads and structured
     * logs remain well-formed while completely removing plaintext secrets.
     */
    public static String mask(String input) {
        if (input == null) {
            return null;
        }

        String masked = KV_PATTERN.matcher(input).replaceAll(match -> {
            String keyQuote = match.group(1);
            String keyName = match.group(2);
            String separator = match.group(3);
            String valQuote = match.group(4);
            if (valQuote != null) {
                return keyQuote + keyName + keyQuote + separator + valQuote + "***MASKED***" + valQuote;
            } else {
                return keyQuote + keyName + keyQuote + separator + "***MASKED***";
            }
        });

        return BEARER_PATTERN.matcher(masked).replaceAll(match -> {
            String prefix = match.group(1);
            String quote = match.group(2);
            return prefix + quote + "***MASKED***" + quote;
        });
    }
}
