package eg.mts.gsuif.generation;

import java.util.Set;
import java.util.HashSet;
import java.util.List;

/** App-owned table namespace shared by entity projection and the later V1 renderer. */
final class CompleteBackendPersistenceNames {
    static final Set<String> SUPPORT_TABLES = Set.of("user_accounts", "user_account_roles", "app_revision",
            "flyway_schema_history");
    // PostgreSQL keeps table, index and sequence names in one relation namespace.
    private static final Set<String> SUPPORT_RELATIONS = Set.of("user_accounts", "user_account_roles",
            "app_revision", "flyway_schema_history", "flyway_schema_history_pk",
            "flyway_schema_history_s_idx", "pk_user_accounts", "uq_user_accounts_username",
            "pk_user_account_roles", "ck_user_account_roles_role", "fk_user_account_roles_user",
            "pk_app_revision", "app_revision_rev_seq");
    static final String AUDIT_SUFFIX = "_aud";

    private CompleteBackendPersistenceNames() { }

    static void requireAvailable(String tableName, String purpose) {
        if (SUPPORT_TABLES.contains(tableName))
            throw new IllegalArgumentException(purpose + " collides with a reserved app-owned table");
        if (SUPPORT_RELATIONS.contains(tableName))
            throw new IllegalArgumentException(purpose + " collides with a reserved app-owned relation");
    }

    static void validateEmittedRelations(String table, String auditTable, List<String> enumChecks) {
        Set<String> used = new HashSet<>(SUPPORT_RELATIONS);
        add(used, table);
        add(used, "pk_" + table);
        for (String check : enumChecks) add(used, check);
        if (auditTable != null) {
            add(used, auditTable);
            add(used, "pk_" + auditTable);
            add(used, "fk_" + auditTable + "_rev");
            for (String check : enumChecks) add(used, check + AUDIT_SUFFIX);
        }
    }

    private static void add(Set<String> used, String name) {
        CompleteBackendEntitySchema.identifier(name, "generated relation or constraint");
        if (!used.add(name))
            throw new IllegalArgumentException("Generated relation-name collision: " + name);
    }
}
