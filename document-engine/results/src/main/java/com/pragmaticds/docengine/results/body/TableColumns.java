package com.pragmaticds.docengine.results.body;

import com.pragmaticds.docengine.results.body.DocumentBody.BodyCell;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceElement;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceSpan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Unmerges a table column the detector addressed as one when the page prints it as several.
 *
 * <h2>The failure this exists to stop</h2>
 *
 * <p>A paystub's earnings grid was served as {@code | ### | ##.## | ##.## ####.## |} — an hours
 * figure and an amount, from two different columns, sharing one cell. A model reading that cannot
 * tell the current period from year-to-date. It answers plausibly, sometimes wrongly, and cites a
 * cell that looks legitimate. Qualifying income is computed from exactly those numbers, so <b>a
 * wrong value here is worse than a missing one</b>, and a merged cell is a wrong value wearing a
 * citation.
 *
 * <h2>The evidence, not a threshold</h2>
 *
 * <p>Nothing here re-reads the page. A cell's text is its member spans joined by a single space
 * ({@code LayoutElementService}), and every span carries the x-extent the parser measured. So a
 * column's ink is already addressed at finer granularity than the column is, and the question "is
 * this one column or several" is answered by the spans themselves:
 *
 * <ol>
 *   <li>Take every span of every cell in one column and cluster them by horizontal OVERLAP. Two
 *       spans that share any x are the same column; a run that shares none with the next is not.
 *       There is no gap threshold, no font metric, no tuned constant — overlap or not.
 *   <li>A column is SPLICED only when some single cell contributes spans to two or more clusters.
 *       Clusters alone are not enough: a column of right-aligned amounts of different lengths can
 *       fail to overlap row-to-row without any cell merging anything, and splitting that would
 *       scatter one column across several for no reason.
 *   <li>A spliced column becomes one output column per cluster. Each span goes to the cluster its
 *       own extent lands in — never to a neighbour — so no value moves to a column it does not
 *       occupy on the paper.
 * </ol>
 *
 * <h2>Guards: a split must be provably lossless, or it does not happen</h2>
 *
 * <p>Splitting rewrites cell text, so it is refused outright unless the rewrite reproduces what
 * was there:
 *
 * <ul>
 *   <li>every cell in the column must carry spans whenever it carries text — text with no spans
 *       cannot be redistributed, and dropping it would delete page content;
 *   <li>every span must be located; a column holding one unreadable span is left as detected
 *       rather than split on partial geometry;
 *   <li>rejoining each cell's spans must reproduce that cell's own text exactly, so the split is a
 *       repartition of the same characters and not a re-transcription;
 *   <li>the result must stay under {@link #MAX_COLUMNS}. Past that the "column" is prose, and a
 *       body must not be expandable into a wall of pipes by one pathological page.
 * </ul>
 *
 * <p>When a column is spliced but a guard refuses the split, the merge is real and unresolvable:
 * the table is marked {@linkplain DocumentBody.TableSpec#columnsTrustworthy() not trustworthy} and
 * the renderer declines to draw a grid at all. That is the deliberate trade — an honest paragraph
 * a reader cannot misread beats a tidy grid that merges two columns' amounts.
 */
final class TableColumns {

    /**
     * The most columns one table may resolve to. A page cannot print more, so a wider result means
     * the span clustering has met something that is not a table.
     */
    private static final int MAX_COLUMNS = 64;

    private TableColumns() {}

    /**
     * @param cells the addressed cells, already in {@code (row, col, id)} order
     * @param addedColumns how many columns the split created, so a DECLARED column count can be
     *     adjusted by the same amount rather than silently contradicted
     * @param trustworthy false when a cell provably holds two columns and cannot be split
     */
    record Resolved(List<BodyCell> cells, int addedColumns, boolean trustworthy) {}

    /** Resolves one table's cells. Pure: same input, same output, always. */
    static Resolved resolve(List<SourceElement> addressedCells) {
        TreeMap<Integer, List<SourceElement>> byColumn = new TreeMap<>();
        for (SourceElement cell : addressedCells) {
            byColumn.computeIfAbsent(cell.cellCol(), column -> new ArrayList<>()).add(cell);
        }

        Map<Integer, Analysis> splitPlan = new LinkedHashMap<>();
        boolean trustworthy = true;
        int added = 0;
        int widest = byColumn.isEmpty() ? 0 : byColumn.lastKey() + 1;

        for (Map.Entry<Integer, List<SourceElement>> column : byColumn.entrySet()) {
            Analysis analysis = analyse(column.getValue());
            if (!spliced(column.getValue(), analysis)) {
                continue;
            }
            if (!splittable(column.getValue())
                    || widest + added + analysis.columns() - 1 > MAX_COLUMNS) {
                // The merge is real and we cannot undo it. Say so rather than serve it as a grid.
                trustworthy = false;
                continue;
            }
            splitPlan.put(column.getKey(), analysis);
            added += analysis.columns() - 1;
        }

        if (splitPlan.isEmpty()) {
            return new Resolved(plain(addressedCells), 0, trustworthy);
        }
        return new Resolved(split(addressedCells, splitPlan), added, trustworthy);
    }

    // ── analysis ────────────────────────────────────────────────────────────

    /**
     * One column's spans, each assigned to the overlapping run it belongs to.
     *
     * <p>Assignment is recorded during clustering rather than recomputed from cluster bounds. Two
     * runs can abut exactly, and a span tested against final bounds could then match both or
     * neither; a span sorted into a run as the run was grown always has exactly one home, which is
     * what makes the split total.
     *
     * @param columns the number of runs; 0 where the column could not be analysed at all
     */
    private record Analysis(int columns, Map<Long, Integer> runBySpan) {}

    /**
     * The column's spans merged into overlapping runs, left to right. Yields no runs where any
     * span is unlocated — an analysis on partial geometry is not evidence of anything.
     */
    private static Analysis analyse(List<SourceElement> cells) {
        List<SourceSpan> located = new ArrayList<>();
        for (SourceElement cell : cells) {
            for (SourceSpan span : cell.spans()) {
                if (!span.located()) {
                    return new Analysis(0, Map.of());
                }
                located.add(span);
            }
        }
        located.sort(Comparator.comparing(SourceSpan::x).thenComparingLong(SourceSpan::id));

        Map<Long, Integer> runBySpan = new LinkedHashMap<>();
        int run = -1;
        BigDecimal right = null;
        for (SourceSpan span : located) {
            // A zero-width span cannot overlap anything, so it joins the run it sits inside rather
            // than opening one of its own: `<= right` where a positive-width span needs `< right`.
            boolean joins =
                    right != null
                            && (span.x().compareTo(right) < 0
                                    || (span.width().signum() == 0
                                            && span.x().compareTo(right) <= 0));
            if (!joins) {
                run++;
                right = span.right();
            } else {
                right = right.max(span.right());
            }
            runBySpan.put(span.id(), run);
        }
        return new Analysis(run + 1, runBySpan);
    }

    /** A column is spliced only when one CELL reaches into two runs. See rule 2. */
    private static boolean spliced(List<SourceElement> cells, Analysis analysis) {
        if (analysis.columns() < 2) {
            return false;
        }
        for (SourceElement cell : cells) {
            Integer first = null;
            for (SourceSpan span : cell.spans()) {
                Integer run = analysis.runBySpan().get(span.id());
                if (first == null) {
                    first = run;
                } else if (!first.equals(run)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether rewriting this column's cells from their spans reproduces exactly what is there. */
    private static boolean splittable(List<SourceElement> cells) {
        for (SourceElement cell : cells) {
            if (cell.spans().isEmpty()) {
                if (!cell.text().isBlank()) {
                    return false; // text no span accounts for: it would vanish
                }
                continue;
            }
            List<String> texts = cell.spans().stream().map(SourceSpan::text).toList();
            if (!String.join(" ", texts).equals(cell.text())) {
                return false;
            }
        }
        return true;
    }

    // ── emission ────────────────────────────────────────────────────────────

    private static List<BodyCell> plain(List<SourceElement> cells) {
        List<BodyCell> out = new ArrayList<>(cells.size());
        for (SourceElement cell : cells) {
            out.add(
                    new BodyCell(
                            cell.id(),
                            cell.cellRow(),
                            cell.cellCol(),
                            cell.box(),
                            cell.text(),
                            cell.spanIds()));
        }
        return out;
    }

    /**
     * The cells at their resolved column indices.
     *
     * <p>A split cell becomes one cell per cluster it occupies and keeps the ORIGINAL element id in
     * every fragment: that element is where the characters came from, and a citation that resolved
     * to anything else would name a row the parser never wrote. A cluster the cell does not reach
     * emits nothing, which renders exactly as the blank it is on the page.
     */
    private static List<BodyCell> split(
            List<SourceElement> cells, Map<Integer, Analysis> splitPlan) {
        List<BodyCell> out = new ArrayList<>();
        for (SourceElement cell : cells) {
            int base = cell.cellCol() + shift(cell.cellCol(), splitPlan);
            Analysis analysis = splitPlan.get(cell.cellCol());
            if (analysis == null) {
                out.add(
                        new BodyCell(
                                cell.id(),
                                cell.cellRow(),
                                base,
                                cell.box(),
                                cell.text(),
                                cell.spanIds()));
                continue;
            }
            for (int index = 0; index < analysis.columns(); index++) {
                int run = index;
                List<SourceSpan> members =
                        cell.spans().stream()
                                .filter(span -> analysis.runBySpan().get(span.id()) == run)
                                .toList();
                if (members.isEmpty()) {
                    continue;
                }
                out.add(
                        new BodyCell(
                                cell.id(),
                                cell.cellRow(),
                                base + index,
                                fragmentBox(cell, members),
                                String.join(
                                        " ", members.stream().map(SourceSpan::text).toList()),
                                members.stream().map(SourceSpan::id).toList()));
            }
        }
        out.sort(
                Comparator.comparingInt(BodyCell::row)
                        .thenComparingInt(BodyCell::col)
                        .thenComparing(BodyCell::layoutElementId));
        return out;
    }

    /** How far right a column moves, given the columns to its left that split. */
    private static int shift(int column, Map<Integer, Analysis> splitPlan) {
        int shift = 0;
        for (Map.Entry<Integer, Analysis> entry : splitPlan.entrySet()) {
            if (entry.getKey() < column) {
                shift += entry.getValue().columns() - 1;
            }
        }
        return shift;
    }

    /**
     * The fragment's own box: measured from its spans horizontally, inherited from the cell
     * vertically. Both halves are things the parser wrote down; neither is a guess.
     */
    private static BodyBox fragmentBox(SourceElement cell, List<SourceSpan> members) {
        BigDecimal left = members.get(0).x();
        BigDecimal right = members.get(0).right();
        for (SourceSpan span : members) {
            left = left.min(span.x());
            right = right.max(span.right());
        }
        BodyBox box = cell.box();
        return new BodyBox(
                left,
                box == null ? null : box.y(),
                right.subtract(left),
                box == null ? null : box.height());
    }
}
