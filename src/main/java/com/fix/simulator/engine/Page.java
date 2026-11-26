package com.fix.simulator.engine;

import java.util.List;

/**
 * One page of a result list.
 *
 * @param total  rows that match the filter in the retained history (not the page size)
 * @param offset index of the first row in {@code rows}, counted from the newest row (0)
 * @param limit  maximum number of rows requested
 * @param rows   the rows on this page
 */
public record Page<T>(long total, int offset, int limit, List<T> rows) {}
