package eg.mts.gsuif.validator;



import com.fasterxml.jackson.databind.JsonNode;

import com.networknt.schema.*;

import org.springframework.stereotype.Component;



import java.util.ArrayList;

import java.util.List;

import java.util.Set;

import java.util.stream.Collectors;



@Component

public class MetadataSchemaValidator {



    public record ValidationError(String path, String message) {}



    private final JsonSchema snapshotSchema;

    private static final String EXPECTED_SCHEMA_VERSION = "1.0.0";



    public MetadataSchemaValidator() {

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);



        SchemaValidatorsConfig config = new SchemaValidatorsConfig();

        // Enforce format constraints such as UUIDs

        config.setFormatAssertionsEnabled(true);



        // We load the metadata-version schema, specifically pointing to the snapshot subschema.

        // The factory will resolve $refs (like component.schema.json) from the same location

        // automatically if they are in the same classpath directory.

        this.snapshotSchema = factory.getSchema(

            SchemaLocation.of("classpath:metadata-version.schema.json#/properties/snapshot"),

            config

        );

    }



    /**

     * Validates a CreateMetadataVersionRequest payload.

     * Missing or unsupported schemaVersion does not suppress a missing snapshot.

     * Snapshot findings use the currently registered schema (1.0.0), even if the version is unknown.

     * @param schemaVersion the incoming schema version

     * @param snapshot the incoming snapshot payload

     * @return a List of ValidationErrors, deterministically ordered. If empty, validation succeeded.

     */

    public List<ValidationError> validate(String schemaVersion, JsonNode snapshot) {

        List<ValidationError> errors = new ArrayList<>();



        if (!EXPECTED_SCHEMA_VERSION.equals(schemaVersion)) {

            errors.add(new ValidationError("$.schemaVersion", "schemaVersion must be a constant value '" + EXPECTED_SCHEMA_VERSION + "'"));

        }



        if (snapshot == null || snapshot.isNull()) {

            errors.add(new ValidationError("$.snapshot", "snapshot is missing but it is required"));

        } else {

            Set<ValidationMessage> messages = snapshotSchema.validate(snapshot);

            List<ValidationError> snapshotErrors = messages.stream()

                    .map(msg -> {

                        String path = msg.getInstanceLocation().toString();

                        if ("required".equals(msg.getType())) {

                            Object[] args = msg.getArguments();

                            if (args != null && args.length > 0 && args[0] instanceof String) {

                                String missingProperty = (String) args[0];

                                path = path.equals("$") ? "$." + missingProperty : path + "." + missingProperty;

                            }

                        }

                        // Make path relative to the request's snapshot field

                        String requestPath = path.startsWith("$.") ? "$.snapshot." + path.substring(2) : "$.snapshot";

                        if (path.equals("$")) {

                            requestPath = "$.snapshot";

                        }

                        return new ValidationError(requestPath, msg.getMessage());

                    })

                    .collect(Collectors.toList());

            errors.addAll(snapshotErrors);

        }



        return errors.stream()

                .sorted((e1, e2) -> {

                    int pathCompare = e1.path().compareTo(e2.path());

                    if (pathCompare != 0) return pathCompare;

                    return e1.message().compareTo(e2.message());

                })

                .collect(Collectors.toList());

    }

}
