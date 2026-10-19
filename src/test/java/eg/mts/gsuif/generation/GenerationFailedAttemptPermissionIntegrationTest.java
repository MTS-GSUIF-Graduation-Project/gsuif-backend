package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.GsuifRole;
import eg.mts.gsuif.entity.GsuifRolePermission;
import eg.mts.gsuif.entity.GsuifRolePermissionId;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.GsuifUserRole;
import eg.mts.gsuif.entity.GsuifUserRoleId;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GsuifPermissionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GenerationFailedAttemptPermissionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private EntityManager entities;
    @Autowired private GsuifPermissionRepository permissions;
    @MockitoBean private BackendModelGateway model;

    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void attemptLookupReturnsOnlyPersistedOwnerStateWithoutGeneration() throws Exception {
        Fixture fixture = fixture("attempt-states", "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        GenerationRun run = entities.find(GenerationRun.class, fixture.runId());
        UUID attemptId = run.getAttemptId();
        String path = "/api/v1/generation/attempts/{id}";
        for (GenerationRunStatus state : List.of(GenerationRunStatus.IN_PROGRESS,
                GenerationRunStatus.SUCCESS, GenerationRunStatus.FAILED)) {
            run.setStatus(state);
            entities.flush();
            var response = mvc.perform(get(path, attemptId).with(authentication(fixture.authentication())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body.runId").value(fixture.runId().toString()))
                    .andExpect(jsonPath("$.body.attemptId").value(attemptId.toString()))
                    .andExpect(jsonPath("$.body.metadataVersionId").value(fixture.versionId().toString()))
                    .andExpect(jsonPath("$.body.status").value(state.name()));
            if (state == GenerationRunStatus.FAILED) {
                response.andExpect(jsonPath("$.body.failureStage").value("AI"))
                        .andExpect(jsonPath("$.body.failureCode").value("MODEL_UNAVAILABLE"));
            } else {
                response.andExpect(jsonPath("$.body.failureStage").value(org.hamcrest.Matchers.nullValue()))
                        .andExpect(jsonPath("$.body.failureCode").value(org.hamcrest.Matchers.nullValue()));
            }
            org.junit.jupiter.api.Assertions.assertEquals(state,
                    entities.find(GenerationRun.class, fixture.runId()).getStatus());
        }
        verifyNoInteractions(model);
    }

    @Test void attemptLookupDoesNotDiscloseOtherUsersOrMissingReservations() throws Exception {
        Fixture fixture = fixture("attempt-hidden", "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        UUID attempt = entities.find(GenerationRun.class, fixture.runId()).getAttemptId();
        Authentication other = new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mvc.perform(get("/api/v1/generation/attempts/{id}", attempt).with(authentication(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
        mvc.perform(get("/api/v1/generation/attempts/{id}", UUID.randomUUID())
                        .with(authentication(fixture.authentication())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/generation/attempts/{id}", attempt).with(anonymous()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(model);
    }

    @Test void attemptLookupRequiresCurrentPageAndVersionGrants() throws Exception {
        for (String missing : List.of("ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion")) {
            Fixture fixture = fixture("attempt-missing-" + UUID.randomUUID().toString().substring(0, 4),
                    missing.equals("ENTITY_ACCESS:GsuifPage")
                            ? "ENTITY_ACCESS:MetadataVersion" : "ENTITY_ACCESS:GsuifPage");
            UUID attempt = entities.find(GenerationRun.class, fixture.runId()).getAttemptId();
            mvc.perform(get("/api/v1/generation/attempts/{id}", attempt)
                            .with(authentication(fixture.authentication())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.statusCode").value(403));
        }
        Fixture revoked = fixture("attempt-revoked", "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        UUID attempt = entities.find(GenerationRun.class, revoked.runId()).getAttemptId();
        var permission = permissions.findByCode("ENTITY_ACCESS:MetadataVersion").orElseThrow();
        entities.remove(entities.find(GsuifRolePermission.class,
                new GsuifRolePermissionId(revoked.roleId(), permission.getId())));
        entities.flush();
        entities.clear();
        mvc.perform(get("/api/v1/generation/attempts/{id}", attempt)
                        .with(authentication(revoked.authentication())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(model);
    }

    @Test void ownerWithBothPersistedGrantsCanReadBoundedFailure() throws Exception {
        Fixture fixture = fixture("both", "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        mvc.perform(get("/api/v1/generation/runs/{id}/failure", fixture.runId())
                        .with(authentication(fixture.authentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.runId").value(fixture.runId().toString()))
                .andExpect(jsonPath("$.body.metadataVersionId").value(fixture.versionId().toString()))
                .andExpect(jsonPath("$.body.failureStage").value("AI"))
                .andExpect(jsonPath("$.body.failureCode").value("MODEL_UNAVAILABLE"))
                .andExpect(header().doesNotExist("X-Generation-Run-Id"));
    }

    @Test void ownerMissingPageGrantGetsStandard403WithoutTrackingHeaders() throws Exception {
        forbidden(fixture("no-page", "ENTITY_ACCESS:MetadataVersion"));
    }

    @Test void ownerMissingMetadataVersionGrantGetsStandard403WithoutTrackingHeaders() throws Exception {
        forbidden(fixture("no-version", "ENTITY_ACCESS:GsuifPage"));
    }

    @Test void anotherUserCannotReadEvenWithPersistedAdminGrants() throws Exception {
        Fixture fixture = fixture("different-owner", "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        Authentication other = new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mvc.perform(get("/api/v1/generation/runs/{id}/failure", fixture.runId())
                        .with(authentication(other)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.statusCode").value(403))
                .andExpect(header().doesNotExist("X-Generation-Run-Id"));
    }

    @Test void anonymousCannotRead() throws Exception {
        Fixture fixture = fixture("anonymous", "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        mvc.perform(get("/api/v1/generation/runs/{id}/failure", fixture.runId()).with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value(401))
                .andExpect(header().doesNotExist("X-Generation-Run-Id"));
    }

    @Test void revokingPersistedGrantImmediatelyDeniesOwner() throws Exception {
        Fixture fixture = fixture("revoked", "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        mvc.perform(get("/api/v1/generation/runs/{id}/failure", fixture.runId())
                        .with(authentication(fixture.authentication())))
                .andExpect(status().isOk());
        var permission = permissions.findByCode("ENTITY_ACCESS:MetadataVersion").orElseThrow();
        var assignment = entities.find(GsuifRolePermission.class,
                new GsuifRolePermissionId(fixture.roleId(), permission.getId()));
        entities.remove(assignment);
        entities.flush();
        entities.clear();
        forbidden(fixture);
    }

    private void forbidden(Fixture fixture) throws Exception {
        mvc.perform(get("/api/v1/generation/runs/{id}/failure", fixture.runId())
                        .with(authentication(fixture.authentication())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FORBIDDEN"))
                .andExpect(jsonPath("$.statusCode").value(403))
                .andExpect(jsonPath("$.clientMessage").value("Access denied"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.errors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(header().doesNotExist("X-Generation-Run-Id"))
                .andExpect(header().doesNotExist("X-Generation-Failure-Stage"))
                .andExpect(header().doesNotExist("X-Generation-Failure-Code"));
    }

    private Fixture fixture(String suffix, String... codes) {
        String unique = UUID.randomUUID().toString().substring(0, 12);
        String username = "diag-" + suffix + "-" + unique;
        GsuifProject project = new GsuifProject();
        project.setName("Failure permission fixture " + suffix);
        entities.persist(project);
        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Failure page");
        entities.persist(page);
        MetadataVersion version = new MetadataVersion();
        version.setPage(page);
        version.setVersion(1);
        version.setSchemaVersion("1.0.0");
        version.setSnapshot("{\"components\":[],\"apiBindings\":[]}");
        entities.persist(version);
        GsuifUser user = new GsuifUser();
        user.setUsername(username);
        user.setPasswordHash("test-only");
        entities.persist(user);
        GsuifRole role = new GsuifRole();
        role.setName("ROLE_DIAG_" + unique);
        entities.persist(role);
        entities.flush();

        GsuifUserRole membership = new GsuifUserRole();
        membership.setId(new GsuifUserRoleId(user.getId(), role.getId()));
        membership.setUser(user);
        membership.setRole(role);
        entities.persist(membership);
        for (String code : codes) {
            var permission = permissions.findByCode(code).orElseThrow();
            GsuifRolePermission assignment = new GsuifRolePermission();
            assignment.setId(new GsuifRolePermissionId(role.getId(), permission.getId()));
            assignment.setRole(role);
            assignment.setPermission(permission);
            entities.persist(assignment);
        }
        GenerationRun run = new GenerationRun();
        run.setMetadataVersion(version);
        run.setTemplateVersion("1.0.0");
        run.setGeneratorVersion("1.0.0");
        run.setToolName("TemplateOnlyProvider");
        run.setToolVersion("1.0.0");
        run.setStatus(GenerationRunStatus.FAILED);
        run.setAttemptId(UUID.randomUUID());
        run.setFailureStage("AI");
        run.setFailureCode("MODEL_UNAVAILABLE");
        run.setTriggeringUser(user);
        entities.persist(run);
        entities.flush();
        Authentication auth = new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority(role.getName())));
        return new Fixture(run.getId(), version.getId(), role.getId(), auth);
    }

    private record Fixture(UUID runId, UUID versionId, UUID roleId, Authentication authentication) { }
}
