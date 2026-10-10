package eg.mts.gsuif.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Iterator;

/** Keeps @Size(min=0) from erasing the minimum implied by @NotBlank in generated documentation. */
@Component
public class NotBlankSchemaConverter implements ModelConverter {
    @Override
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) return null;
        Schema resolved = chain.next().resolve(type, context, chain);
        if (resolved == null || type.getType() == null) return resolved;

        Class<?> dto = TypeFactory.defaultInstance().constructType(type.getType()).getRawClass();
        Schema model = resolved.get$ref() == null ? resolved
                : context.getDefinedModels().get(resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1));
        if (model == null || model.getProperties() == null) return resolved;

        for (Field field : dto.getDeclaredFields()) {
            if (!field.isAnnotationPresent(NotBlank.class)) continue;
            JsonProperty jsonProperty = field.getAnnotation(JsonProperty.class);
            String name = jsonProperty == null || jsonProperty.value().isEmpty() ? field.getName() : jsonProperty.value();
            Schema property = (Schema) model.getProperties().get(name);
            if (property != null) {
                // This is schema metadata only; Bean Validation and normalization remain unchanged.
                property.setMinLength(Math.max(1, property.getMinLength() == null ? 0 : property.getMinLength()));
            }
        }
        return resolved;
    }
}
