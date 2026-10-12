package eg.mts.gsuif.security;

import eg.mts.gsuif.dto.UpdateWorkOrderRequest;
import eg.mts.gsuif.entity.GsuifPermission;
import eg.mts.gsuif.entity.GsuifRole;
import eg.mts.gsuif.entity.GsuifRolePermission;
import eg.mts.gsuif.entity.GsuifRolePermissionId;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.GsuifUserRole;
import eg.mts.gsuif.entity.GsuifUserRoleId;
import eg.mts.gsuif.entity.WorkOrder;
import eg.mts.gsuif.entity.WorkOrderStatus;
import eg.mts.gsuif.repository.GsuifPermissionRepository;
import eg.mts.gsuif.repository.GsuifRolePermissionRepository;
import eg.mts.gsuif.repository.GsuifRoleRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.GsuifUserRoleRepository;
import eg.mts.gsuif.repository.WorkOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FieldDenyWriteScopeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WorkOrderRepository workOrderRepository;
    @Autowired private GsuifUserRepository userRepository;
    @Autowired private GsuifRoleRepository roleRepository;
    @Autowired private GsuifUserRoleRepository userRoleRepository;
    @Autowired private GsuifPermissionRepository permissionRepository;
    @Autowired private GsuifRolePermissionRepository rolePermissionRepository;

    @Test
    @WithMockUser(username = "scrum61-field-write", authorities = "ROLE_SCRUM61_FIELD_WRITE")
    void update_fieldDeniedAssignedTo_writesValueButMasksResponse() throws Exception {
        createUserWithPermissions();
        WorkOrder saved = workOrderRepository.save(new WorkOrder(
                "WO-SCRUM61-FIELD-WRITE", WorkOrderStatus.OPEN,
                LocalDate.of(2026, 10, 10), "old.assignee"));
        UpdateWorkOrderRequest request = new UpdateWorkOrderRequest(
                saved.getOrderNumber(), WorkOrderStatus.IN_PROGRESS,
                LocalDate.of(2026, 10, 11), "new.assignee");

        mockMvc.perform(put("/api/v1/work-orders/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.assignedTo").value(nullValue()));

        assertThat(workOrderRepository.findById(saved.getId()).orElseThrow().getAssignedTo())
                .isEqualTo("new.assignee");
    }

    private void createUserWithPermissions() {
        GsuifUser user = new GsuifUser();
        user.setUsername("scrum61-field-write");
        user.setPasswordHash("test-only");
        user = userRepository.save(user);
        GsuifRole role = new GsuifRole();
        role.setName("ROLE_SCRUM61_FIELD_WRITE");
        role = roleRepository.save(role);
        GsuifUserRole userRole = new GsuifUserRole();
        userRole.setId(new GsuifUserRoleId(user.getId(), role.getId()));
        userRole.setUser(user);
        userRole.setRole(role);
        userRoleRepository.save(userRole);
        assign(role, "ENTITY_ACCESS:WorkOrder");
        GsuifPermission fieldDeny = permissionRepository.findByCode("FIELD_DENY:WorkOrder:assignedTo")
                .orElseGet(() -> {
                    GsuifPermission permission = new GsuifPermission();
                    permission.setCode("FIELD_DENY:WorkOrder:assignedTo");
                    permission.setName("Hide work-order assignee");
                    return permissionRepository.save(permission);
                });
        assign(role, fieldDeny);
    }

    private void assign(GsuifRole role, String code) {
        assign(role, permissionRepository.findByCode(code).orElseThrow());
    }

    private void assign(GsuifRole role, GsuifPermission permission) {
        GsuifRolePermission assignment = new GsuifRolePermission();
        assignment.setId(new GsuifRolePermissionId(role.getId(), permission.getId()));
        assignment.setRole(role);
        assignment.setPermission(permission);
        rolePermissionRepository.save(assignment);
    }
}
