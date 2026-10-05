package io.replaydock.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** Assigned IDs are non-null before insertion; tell Spring Data when to persist rather than merge. */
@MappedSuperclass
public abstract class AssignedEntity<ID> implements Persistable<ID> {
    @Transient
    private boolean newEntity = true;
    @Override public boolean isNew() { return newEntity; }
    @PostLoad @PostPersist
    protected void markPersisted() { newEntity = false; }
}
