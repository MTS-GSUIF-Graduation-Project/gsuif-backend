<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.logging;

public final class SensitiveDataMasker {
    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile(
            "(?i)([\"']?(?:password|passwd|token|secret|authorization|api[-_]?key)[\"']?\\s*[=:]\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
                    + "|(?:basic|bearer)\\s+[^\\s,;}\\]]+|[^\\s,;}\\]]+)" );
    private static final java.util.regex.Pattern AUTH_SCHEME = java.util.regex.Pattern.compile(
            "(?i)\\b(basic|bearer)\\s+[^\\s,;}\\]]+" );

    private SensitiveDataMasker() { }

    public static String mask(String value) {
        if (value == null) return null;
        String masked = KEY.matcher(value).replaceAll(match -> {
            String original = match.group(2);
            String replacement = original.startsWith("\"") ? "\"[REDACTED]\""
                    : original.startsWith("'") ? "'[REDACTED]'" : "[REDACTED]";
            return java.util.regex.Matcher.quoteReplacement(match.group(1) + replacement);
        });
        return AUTH_SCHEME.matcher(masked).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(match.group(1) + " [REDACTED]"));
    }
}
