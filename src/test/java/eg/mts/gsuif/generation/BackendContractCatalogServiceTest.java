package eg.mts.gsuif.generation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackendContractCatalogServiceTest {
    @Test void listsOnlyEligibleProductionVersionsInStableOrder() {
        var listed = new BackendContractCatalogService().list();
        assertEquals(List.of("asset-ticket-api@2.0.0", "stock-unit-api@2.0.0", "stock-unit-api@3.0.0"),
                listed.stream().map(item -> item.contractId() + "@" + item.contractVersion()).toList());
        assertTrue(listed.stream().allMatch(item -> item.updateMode().equals("FULL")
                && !item.pageCompatibilityChecked() && !item.label().isBlank()
                && item.operationIds().size() == 5));
        assertEquals(List.of("registerStockUnit", "inspectStockUnit", "scanStockUnits",
                "replaceStockUnit", "discardStockUnit"), listed.getLast().operationIds());
        assertEquals(listed, new BackendContractCatalogService().list());
        assertTrue(listed.stream().noneMatch(item -> item.contractId().contains("gadget")
                || item.contractVersion().equals("1.0.0")));
    }
}
