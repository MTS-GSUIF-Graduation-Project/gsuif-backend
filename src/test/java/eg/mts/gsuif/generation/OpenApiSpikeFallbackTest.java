package eg.mts.gsuif.generation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression check for DEC-022's returnTypes fallback in the shared Mustache override. */
class OpenApiSpikeFallbackTest {

    @Test
    void workOrderSpikeKeepsItsOriginalGeneratedReturnTypes() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("openapiSpikeRequired"),
                "Run with -Popenapi-spike to generate the WorkOrder interface");

        try (InputStream stream = getClass().getResourceAsStream("/openapi/work-order-api.yaml")) {
            assertNotNull(stream);
            assertFalse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("x-gsuif-payload-java-type"), "The spike must exercise the fallback branch");
        }

        Path generatedSource = Path.of("target/generated-sources/openapi/src/main/java/eg/mts/gsuif/api/WorkOrderApi.java");
        assertTrue(Files.isRegularFile(generatedSource), "The profile must generate the interface from the spike spec");
        Class<?> api = Class.forName("eg.mts.gsuif.api.WorkOrderApi");
        Class<?> dto = Class.forName("eg.mts.gsuif.dto.generated.WorkOrderDto");
        Class<?> status = Class.forName("eg.mts.gsuif.dto.generated.WorkOrderStatus");
        Class<?> createRequest = Class.forName("eg.mts.gsuif.dto.generated.CreateWorkOrderRequest");
        Class<?> updateRequest = Class.forName("eg.mts.gsuif.dto.generated.UpdateWorkOrderRequest");

        assertReturn(api.getMethod("createWorkOrder", createRequest), dto.getName());
        assertReturn(api.getMethod("getWorkOrderById", UUID.class), dto.getName());
        assertReturn(api.getMethod("getWorkOrders", status), "java.util.List<" + dto.getName() + ">");
        assertReturn(api.getMethod("updateWorkOrder", UUID.class, updateRequest), dto.getName());
        assertReturn(api.getMethod("deleteWorkOrder", UUID.class), dto.getName());
    }

    private void assertReturn(Method method, String payloadType) {
        assertEquals("org.springframework.http.ResponseEntity<eg.mts.gsuif.dto.ApiResponse<"
                + payloadType + ">>", method.getGenericReturnType().getTypeName());
    }
}
