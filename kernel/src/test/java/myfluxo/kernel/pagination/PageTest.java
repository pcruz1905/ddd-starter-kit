package myfluxo.kernel.pagination;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageTest {

    @Test
    void totalPages_roundsUp() {
        var page = new Page<>(List.of("a"), 0, 10, 25);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void emptyPage_hasZeroTotalPages() {
        var page = Page.<String>empty(PageRequest.of(0, 10));
        assertThat(page.totalPages()).isEqualTo(0);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.hasPrevious()).isFalse();
    }

    @Test
    void hasNext_isTrueWhenMorePagesExist() {
        var page = new Page<>(List.of("a", "b", "c"), 0, 3, 10);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void hasPrevious_isFalseOnFirstPage() {
        var page = new Page<>(List.of("a"), 0, 10, 100);
        assertThat(page.hasPrevious()).isFalse();
    }

    @Test
    void map_preservesPagingMetadata() {
        var page = new Page<>(List.of(1, 2, 3), 1, 3, 100);

        var mapped = page.map(i -> "#" + i);

        assertThat(mapped.items()).containsExactly("#1", "#2", "#3");
        assertThat(mapped.pageNumber()).isEqualTo(1);
        assertThat(mapped.totalElements()).isEqualTo(100);
    }

    @Test
    void pageRequest_offsetIsPageTimesSize() {
        assertThat(PageRequest.of(3, 25).offset()).isEqualTo(75L);
    }

    @Test
    void pageRequest_rejectsNegativePageNumber() {
        assertThatThrownBy(() -> PageRequest.of(-1, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageRequest_rejectsZeroPageSize() {
        assertThatThrownBy(() -> PageRequest.of(0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
