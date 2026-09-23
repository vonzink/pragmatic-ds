package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.dto.ToolAdapterRunDto;
import com.pragmaticds.rag.repository.BrainToolAdapterRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the tool-adapter execution audit trail. The recorder writes a
 * sanitized row per adapter call; this exposes the recent rows so an operator can
 * investigate failing or abusive adapters from the admin API/dashboard instead of
 * needing psql on the production database.
 */
@Service
public class ToolAdapterRunService {

    private final BrainToolAdapterRunRepository runs;

    public ToolAdapterRunService(BrainToolAdapterRunRepository runs) {
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public List<ToolAdapterRunDto> recentRuns(UUID brainId, String toolName) {
        List<com.pragmaticds.rag.domain.BrainToolAdapterRun> rows = (toolName == null || toolName.isBlank())
                ? runs.findTop25ByBrainIdOrderByCreatedAtDesc(brainId)
                : runs.findTop25ByBrainIdAndToolNameOrderByCreatedAtDesc(brainId, toolName.strip());
        return rows.stream().map(ToolAdapterRunDto::from).toList();
    }
}
