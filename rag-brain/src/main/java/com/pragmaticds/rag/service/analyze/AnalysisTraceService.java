package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.provider.AiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Records analyze-run METADATA ONLY — never document bytes or extracted text.
 * v1 logs a structured line (no new corpus/audit table on the rag side; the suite
 * persists the authoritative folder_brain_run row). Doc ids/count/pageCount/tokens/
 * status only; this is the statelessness invariant (spec §6).
 */
@Service
public class AnalysisTraceService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTraceService.class);

    /**
     * @param attempts model calls made for this run (2 when the v2 corrective retry fired)
     * @param inTok    prompt tokens summed across ALL attempts — must agree with the AnalysisResult
     * @param outTok   completion tokens summed across ALL attempts
     */
    public void record(UUID brainId, String analyzerSlug, AnalysisContext ctx,
                       DocumentBlockService.BuildResult built, AiResponse response,
                       int attempts, int inTok, int outTok, String status) {
        int docCount = ctx.docs() == null ? 0 : ctx.docs().size();
        log.info("analyze brain={} analyzer={} docs={} sent={} skipped={} pages={} provider={} model={} attempts={} inTok={} outTok={} status={}",
                brainId, analyzerSlug, docCount, built.blocks().size(), built.skipped().size(),
                built.pageCount(),
                response == null ? null : response.providerName(),
                response == null ? null : response.modelName(),
                attempts, inTok, outTok,
                status);
    }
}
