package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.config.WorkerParserAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whole-package renders buffered every page PNG in memory on both sides (Phase 2
 * review). Batching bounds the exposure; the chunk arithmetic is the part that can
 * silently drop or duplicate a page, so it is pinned exactly.
 */
class RenderBatchingTest {

    @Test
    void pages_are_chunked_without_loss_or_overlap() {
        assertThat(WorkerParserAdapter.renderBatches(5, 2))
                .containsExactly(List.of(0, 1), List.of(2, 3), List.of(4));
        assertThat(WorkerParserAdapter.renderBatches(8, 8)).containsExactly(
                List.of(0, 1, 2, 3, 4, 5, 6, 7));
        assertThat(WorkerParserAdapter.renderBatches(1, 8)).containsExactly(List.of(0));
    }

    @Test
    void unknown_page_count_renders_the_whole_file_in_one_call() {
        // Images carry no page count at ingestion — they are one page by construction.
        assertThat(WorkerParserAdapter.renderBatches(null, 8)).containsExactly(List.of());
        assertThat(WorkerParserAdapter.renderBatches(0, 8)).containsExactly(List.of());
    }
}
