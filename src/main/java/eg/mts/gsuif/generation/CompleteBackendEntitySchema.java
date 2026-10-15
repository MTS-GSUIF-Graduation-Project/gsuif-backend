package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable entity-table projection for the later V1 renderer; it emits no application file. */
record CompleteBackendEntitySchema(String tableName, String auditTableName, List<Column> columns,
                                   List<Constraint> constraints, String sqlFragment) {
    record Column(String name, String sqlType, boolean nullable, boolean primaryKey) { }
    record Constraint(String name, String column, List<String> enumValues) {
        Constraint { enumValues = List.copyOf(enumValues); }
    }

    CompleteBackendEntitySchema {
        columns = List.copyOf(columns);
        constraints = List.copyOf(constraints);
    }

    private static final Set<String> SUPPORT_COLUMNS = Set.of("id", "created_at", "updated_at",
            "created_by", "last_modified_by");
    private static final Set<String> SQL_RESERVED = Set.of("all", "analyse", "analyze", "and", "any", "array",
            "as", "asc", "asymmetric", "authorization", "between", "binary", "both", "case", "cast",
            "check", "collate", "collation", "column", "concurrently", "constraint", "create", "cross",
            "current_catalog", "current_date", "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct", "do",
            "else", "end", "except", "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant",
            "group", "having", "ilike", "in", "initially", "inner", "intersect", "into", "is", "isnull",
            "join", "lateral", "leading", "left", "like", "limit", "localtime", "localtimestamp",
            "natural", "not", "notnull", "null", "offset",
            "on", "only", "or", "order", "outer", "overlaps", "placing", "primary", "references",
            "returning", "right", "select", "session_user", "similar", "some", "symmetric",
            "system_user", "table", "tablesample", "then", "to", "trailing", "true", "union",
            "unique", "user", "using", "values", "variadic", "verbose", "view", "when", "where",
            "window", "with");
    private static final Configuration TEMPLATES = templates();

    static CompleteBackendEntitySchema from(CompleteOutputDescriptor descriptor) {
        if (descriptor == null || descriptor.mode() == OutputMode.FRONTEND)
            throw new IllegalArgumentException("Entity schema requires BACKEND or FULL_STACK descriptor");
        identifier(descriptor.tableName(), "entity table");
        CompleteBackendPersistenceNames.requireAvailable(descriptor.tableName(), "Entity table");
        String auditTable = null;
        if (descriptor.enversAudited()) {
            auditTable = descriptor.tableName() + CompleteBackendPersistenceNames.AUDIT_SUFFIX;
            identifier(auditTable, "derived audit table");
            CompleteBackendPersistenceNames.requireAvailable(auditTable, "Derived audit table");
        }
        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        Set<String> usedColumns = new HashSet<>();
        Set<String> usedConstraints = new HashSet<>();
        add(columns, usedColumns, new Column("id", "uuid", false, true));
        String primaryName = "pk_" + descriptor.tableName();
        identifier(primaryName, "primary-key constraint");
        usedConstraints.add(primaryName);

        List<FieldSpec> fields = new ArrayList<>(descriptor.fields());
        if (fields.stream().anyMatch(field -> field == null || field.columnName() == null))
            throw new IllegalArgumentException("Entity column name is required");
        fields.sort(java.util.Comparator.comparing(FieldSpec::columnName));
        for (FieldSpec field : fields) {
            identifier(field.columnName(), "entity column");
            if (SUPPORT_COLUMNS.contains(field.columnName()))
                throw new IllegalArgumentException("Entity column collides with inherited support column");
            String sqlType;
            if (field.enumType() != null) {
                if (!field.enumType().equals(field.javaType()))
                    throw new IllegalArgumentException("Entity enum type differs from Java type");
                int length = length(field);
                List<String> values = enumValues(descriptor, field);
                if (values.isEmpty() || values.stream().anyMatch(value -> value == null
                        || !value.matches("[A-Z][A-Z0-9_]*") || value.length() > length))
                    throw new IllegalArgumentException("Selected enum values are invalid or exceed column length");
                sqlType = "varchar(" + length + ")";
                String checkName = "ck_" + descriptor.tableName() + "_" + field.columnName() + "_enum";
                identifier(checkName, "enum CHECK constraint");
                if (!usedConstraints.add(checkName))
                    throw new IllegalArgumentException("Generated constraint-name collision");
                constraints.add(new Constraint(checkName, field.columnName(), values));
            } else {
                sqlType = switch (field.javaType()) {
                    case "String" -> "varchar(" + length(field) + ")";
                    case "UUID" -> "uuid";
                    case "LocalDate" -> "date";
                    case "LocalDateTime" -> "timestamp(6) without time zone";
                    case "Integer" -> "integer";
                    case "Long" -> "bigint";
                    case "Boolean" -> "boolean";
                    default -> throw new IllegalArgumentException("Unsupported complete-output persistence field type");
                };
                if (field.columnLength() != null && !"String".equals(field.javaType()))
                    throw new IllegalArgumentException("Length is supported only for textual fields");
            }
            add(columns, usedColumns, new Column(field.columnName(), sqlType, field.nullable(), false));
        }
        add(columns, usedColumns, new Column("created_at", "timestamp(6) with time zone", false, false));
        add(columns, usedColumns, new Column("updated_at", "timestamp(6) with time zone", false, false));
        add(columns, usedColumns, new Column("created_by", "varchar(100)", false, false));
        add(columns, usedColumns, new Column("last_modified_by", "varchar(100)", false, false));
        constraints.sort(java.util.Comparator.comparing(Constraint::name));
        String sql = render(descriptor.tableName(), columns, primaryName, constraints);
        return new CompleteBackendEntitySchema(descriptor.tableName(), auditTable, columns, constraints, sql);
    }

    private static List<String> enumValues(CompleteOutputDescriptor descriptor, FieldSpec field) {
        var create = descriptor.createFields().get(field.name());
        var update = descriptor.editableFields().get(field.name());
        var response = descriptor.responseFields().get(field.name());
        if (create == null || update == null || response == null
                || !field.enumType().equals(create.enumName())
                || !create.values().equals(update.values()) || !create.values().equals(response.values()))
            throw new IllegalArgumentException("Selected enum definitions differ across contract DTOs");
        return List.copyOf(create.values());
    }

    private static int length(FieldSpec field) {
        int length = field.columnLength() == null ? 255 : field.columnLength();
        if (length < 1) throw new IllegalArgumentException("Textual column length must be positive");
        return length;
    }

    private static void add(List<Column> columns, Set<String> names, Column column) {
        if (!names.add(column.name())) throw new IllegalArgumentException("Duplicate entity column");
        columns.add(column);
    }

    private static void identifier(String value, String purpose) {
        if (value == null || !value.matches("[a-z][a-z0-9_]*")
                || value.getBytes(StandardCharsets.UTF_8).length > 63
                || SQL_RESERVED.contains(value.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Invalid or reserved PostgreSQL identifier for " + purpose);
    }

    private static Configuration templates() {
        Configuration config = new Configuration(Configuration.VERSION_2_3_34);
        config.setClassForTemplateLoading(CompleteBackendEntitySchema.class, "/");
        config.setDefaultEncoding(StandardCharsets.UTF_8.name());
        config.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        config.setLogTemplateExceptions(false);
        return config;
    }

    private static String render(String table, List<Column> columns, String primary, List<Constraint> checks) {
        try {
            List<Map<String, Object>> columnModel = columns.stream().map(column -> Map.<String, Object>of(
                    "name", column.name(), "sqlType", column.sqlType(), "nullable", column.nullable())).toList();
            List<Map<String, Object>> checkModel = checks.stream().map(check -> Map.<String, Object>of(
                    "name", check.name(), "column", check.column(), "enumValues", check.enumValues())).toList();
            StringWriter writer = new StringWriter();
            TEMPLATES.getTemplate("templates/complete-backend/entity-schema.sql.ftl")
                    .process(Map.of("table", table, "columns", columnModel, "primary", primary,
                            "checks", checkModel), writer);
            return writer.toString().replace("\r\n", "\n");
        } catch (Exception ex) { throw new IllegalArgumentException("Could not render entity schema projection", ex); }
    }
}
