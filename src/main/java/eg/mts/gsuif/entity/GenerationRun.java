package eg.mts.gsuif.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * JPA mapping for {@code gsuif_generation_run}.
 */
@Entity
@Table(
        name = "gsuif_generation_run",
        indexes = {
                @Index(name = "idx_gsuif_generation_run_metadata_version_id", columnList = "metadata_version_id"),
                @Index(name = "idx_gsuif_generation_run_triggering_user_id", columnList = "triggering_user_id")
        }
)
@Check(name = "ck_gsuif_generation_run_status", constraints = "status IN ('SUCCESS', 'BUILD_FAILED')")
public class GenerationRun extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(
            name = "metadata_version_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsuif_generation_run_metadata_version_id")
    )
    private MetadataVersion metadataVersion;

    @Column(name = "template_version", nullable = false, length = 100)
    private String templateVersion;

    @Column(name = "generator_version", nullable = false, length = 100)
    private String generatorVersion;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Column(name = "tool_version", nullable = false, length = 100)
    private String toolVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerationRunStatus status;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(
            name = "triggering_user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsuif_generation_run_triggering_user_id")
    )
    private GsuifUser triggeringUser;

    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public MetadataVersion getMetadataVersion() {
        return metadataVersion;
    }

    public void setMetadataVersion(MetadataVersion metadataVersion) {
        this.metadataVersion = metadataVersion;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(String templateVersion) {
        this.templateVersion = templateVersion;
    }

    public String getGeneratorVersion() {
        return generatorVersion;
    }

    public void setGeneratorVersion(String generatorVersion) {
        this.generatorVersion = generatorVersion;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public void setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
    }

    public GenerationRunStatus getStatus() {
        return status;
    }

    public void setStatus(GenerationRunStatus status) {
        this.status = status;
    }

    public GsuifUser getTriggeringUser() {
        return triggeringUser;
    }

    public void setTriggeringUser(GsuifUser triggeringUser) {
        this.triggeringUser = triggeringUser;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GenerationRun that)) {
            return false;
        }
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return GenerationRun.class.hashCode();
    }
}
