package myfluxo.kernel.pagination;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results plus enough metadata for clients to navigate. Returned
 * by repository methods that accept a {@link PageRequest}.
 */
public record Page<T>(
    List<T> items,
    int pageNumber,
    int pageSize,
    long totalElements
) {

    public Page {
        if (pageNumber < 0) {
            throw new IllegalArgumentException("pageNumber must be >= 0");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must be >= 0");
        }
        items = List.copyOf(items);
    }

    public static <T> Page<T> empty(PageRequest request) {
        return new Page<>(List.of(), request.pageNumber(), request.pageSize(), 0);
    }

    public static <T> Page<T> of(List<T> items, PageRequest request, long totalElements) {
        return new Page<>(items, request.pageNumber(), request.pageSize(), totalElements);
    }

    public int totalPages() {
        if (totalElements == 0) return 0;
        return (int) Math.ceilDiv(totalElements, pageSize);
    }

    public boolean hasNext() {
        return (long) (pageNumber + 1) * (long) pageSize < totalElements;
    }

    public boolean hasPrevious() {
        return pageNumber > 0;
    }

    /** Project each item without losing the page metadata. */
    public <U> Page<U> map(Function<? super T, ? extends U> f) {
        List<U> mapped = items.stream().<U>map(f).toList();
        return new Page<>(mapped, pageNumber, pageSize, totalElements);
    }
}
