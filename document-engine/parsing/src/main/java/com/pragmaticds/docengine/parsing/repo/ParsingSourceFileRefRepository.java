package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.ParsingSourceFileRef;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Content-type lookup only — see {@link ParsingSourceFileRef}. */
public interface ParsingSourceFileRefRepository extends JpaRepository<ParsingSourceFileRef, UUID> {

    /** Batched: one query for a whole package's pages, never one per page. */
    List<ParsingSourceFileRef> findByIdIn(Collection<UUID> ids);
}
