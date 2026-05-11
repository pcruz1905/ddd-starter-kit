package myfluxo.kernel.pagination;

import java.util.List;

/**
 * Caller-side intent for a paginated query. Zero-indexed pages.
 *
 * <p>The {@code sort} list is treated as an ordered list of tie-breakers —
 * the first entry is the primary sort, the second resolves ties, etc.
 * Repository adapters translate field names to their underlying columns;
 * the domain doesn't care how.
 */
public record PageRequest(
    int pageNumber,
    int pageSize,
    List<Sort> sort
) {

    public PageRequest {
        if (pageNumber < 0) {
            throw new IllegalArgumentException("pageNumber must be >= 0, was " + pageNumber);
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0, was " + pageSize);
        }
        sort = List.copyOf(sort);
    }

    public static PageRequest of(int pageNumber, int pageSize) {
        return new PageRequest(pageNumber, pageSize, List.of());
    }

    public static PageRequest of(int pageNumber, int pageSize, Sort first, Sort... rest) {
        var all = new java.util.ArrayList<Sort>(1 + rest.length);
        all.add(first);
        java.util.Collections.addAll(all, rest);
        return new PageRequest(pageNumber, pageSize, all);
    }

    public long offset() {
        return (long) pageNumber * (long) pageSize;
    }

    /**
     * A single sort term — field name + direction. Field names are
     * domain-facing identifiers ({@code "createdAt"}, {@code "email"}); the
     * persistence adapter maps them to real columns.
     */
    public record Sort(String field, SortDirection direction) {
        public Sort {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("Sort field cannot be null/blank");
            }
            if (direction == null) {
                throw new IllegalArgumentException("Sort direction cannot be null");
            }
        }

        public static Sort asc(String field) { return new Sort(field, SortDirection.ASC); }
        public static Sort desc(String field) { return new Sort(field, SortDirection.DESC); }
    }
}
