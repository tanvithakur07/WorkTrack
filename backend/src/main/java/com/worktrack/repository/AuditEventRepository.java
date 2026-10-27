package com.worktrack.repository;

import com.worktrack.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Only AuditService writes to this and only AuditService reads from it. No code path updates or deletes
 * audit rows (the entity is @Immutable); tests are the only callers of deleteAll.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
}
