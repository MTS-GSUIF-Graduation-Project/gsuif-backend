package eg.mts.gsuif.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * JPA mapping for {@code gsuif_generated_artifact}.
 *
 * <p>{@code component_id} is a nullable UUID with no foreign key (no component table).
 */
@Entity
@Table(
        name = "gsuif_generated_artifact",
        indexes = @Index(name = "idx_gsuif_generated_artifact_generation_run_id", columnList = "generation_run_id")
)
public class GeneratedArtifact extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(
            name = "generation_run_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsuif_generated_artifact_generation_run_id")
    )
    private GenerationRun generationRun;

    @Column(name = "artifact_name", nullable = false, length = 255)
    private String artifactName;

    @Column(name = "artifact_type", nullable = false, length = 100)
    private String artifactType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "component_id", length = 36)
    private UUID componentId;

    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public GenerationRun getGenerationRun() {
        return generationRun;
    }

    public void setGenerationRun(GenerationRun generationRun) {
        this.generationRun = generationRun;
    }

    public String getArtifactName() {
        return artifactName;
    }

    public void setArtifactName(String artifactName) {
        this.artifactName = artifactName;
    }

    public String getArtifactType() {
        return artifactType;
    }

    public void setArtifactType(String artifactType) {
        this.artifactType = artifactType;
    }

    public UUID getComponentId() {
        return componentId;
    }

    public void setComponentId(UUID componentId) {
        this.componentId = componentId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GeneratedArtifact that)) {
            return false;
        }
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return GeneratedArtifact.class.hashCode();
    }
}
