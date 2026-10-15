package eg.mts.gsuif.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

/** Uses the identity already defined by the deployed revinfo baseline. */
@Entity
@Table(name = "revinfo")
@RevisionEntity
public class RevisionInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    @Column(name = "rev")
    private Integer revision;

    @RevisionTimestamp
    @Column(name = "revtstmp")
    private Long timestamp;

    public Integer getRevision() {
        return revision;
    }

    public Long getTimestamp() {
        return timestamp;
    }
}
