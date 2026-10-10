package eg.mts.gsuif.dto;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PagedBodyTest {

    // ── PagedBody.of(Page) ────────────────────────────────────────────────────

    @Test
    void of_mapsAllFiveStd04FieldsFromSpringPage() {
        List<String> content = List.of("alpha", "beta", "gamma");
        PageRequest pageable = PageRequest.of(1, 3);   // page index 1, size 3
        Page<String> page = new PageImpl<>(content, pageable, 9); // 9 total elements

        PagedBody<String> body = PagedBody.of(page);

        assertThat(body.data()).containsExactly("alpha", "beta", "gamma");
        assertThat(body.totalPages()).isEqualTo(3);        // ceil(9/3)
        assertThat(body.totalElements()).isEqualTo(9L);
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.number()).isEqualTo(1);            // 0-based page index
    }

    @Test
    void of_firstPage_hasNumberZero() {
        Page<Integer> page = new PageImpl<>(List.of(1, 2), PageRequest.of(0, 2), 4);

        PagedBody<Integer> body = PagedBody.of(page);

        assertThat(body.number()).isEqualTo(0);
        assertThat(body.totalPages()).isEqualTo(2);
    }

    @Test
    void of_emptyPage_allCountersAreZero() {
        Page<String> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

        PagedBody<String> body = PagedBody.of(empty);

        assertThat(body.data()).isEmpty();
        assertThat(body.totalPages()).isEqualTo(0);
        assertThat(body.totalElements()).isEqualTo(0L);
    }

    // ── ApiResponse.page(PagedBody, clientMessage) ────────────────────────────

    @Test
    void apiResponse_page_wrapsPagedBodyWithOkEnvelope() {
        PagedBody<String> pagedBody = new PagedBody<>(List.of("x"), 2, 4L, 2, 0);

        ApiResponse<PagedBody<String>> response = ApiResponse.page(pagedBody, "Items retrieved");

        // STD-01: exactly these 5 fields
        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.clientMessage()).isEqualTo("Items retrieved");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isSameAs(pagedBody);
        assertThat(response.errors()).isNull();              // STD-02: errors null on success
    }

    @Test
    void apiResponse_page_bodyContainsCorrectPaginationFields() {
        List<String> items = List.of("a", "b");
        PagedBody<String> pagedBody = new PagedBody<>(items, 5, 10L, 2, 3);

        ApiResponse<PagedBody<String>> response = ApiResponse.page(pagedBody, "OK");

        PagedBody<String> body = response.body();
        assertThat(body.data()).containsExactly("a", "b");
        assertThat(body.totalPages()).isEqualTo(5);
        assertThat(body.totalElements()).isEqualTo(10L);
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.number()).isEqualTo(3);
    }
}
