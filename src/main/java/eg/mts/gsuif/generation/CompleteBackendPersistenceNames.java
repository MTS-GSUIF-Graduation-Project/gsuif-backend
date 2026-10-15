package eg.mts.gsuif.generation;

import java.util.Set;

/** App-owned table namespace shared by entity projection and the later V1 renderer. */
final class CompleteBackendPersistenceNames {
    static final Set<String> SUPPORT_TABLES = Set.of("user_accounts", "user_account_roles", "app_revision",
            "flyway_schema_history");
    static final String AUDIT_SUFFIX = "_aud";

    private CompleteBackendPersistenceNames() { }

    static void requireAvailable(String tableName, String purpose) {
        if (SUPPORT_TABLES.contains(tableName))
            throw new IllegalArgumentException(purpose + " collides with a reserved app-owned table");
    }
}
