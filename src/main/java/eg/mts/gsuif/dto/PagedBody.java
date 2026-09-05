package eg.mts.gsuif.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * STD-04 — Paginated body shape.
 *
 * <p>Used as the {@code body} field of {@link ApiResponse} whenever an endpoint returns a paginated
 * result. The five fields below are the exact contract defined by STD-04 in standards-checklist.md.
 *
 * <p>Usage:
 * <pre>{@code
 * Page<UserDto> page = userService.findAll(pageable);
 * return ResponseEntity.ok(ApiResponse.page(PagedBody.of(page), "Users retrieved"));
 * }</pre>
 *
 * @param <T> the type of each element in {@code data}
 */
public record PagedBody<T>(
        List<T> data,
        int totalPages,
        long totalElements,
        int size,
        int number
) {

    /**
     * Convenience factory that maps a Spring Data {@link Page} to a {@link PagedBody}.
     *
     * @param page the Spring Data page result
     * @param <T>  element type
     * @return a {@code PagedBody} with all five STD-04 fields populated
     */
    public static <T> PagedBody<T> of(Page<T> page) {
        return new PagedBody<>(
                page.getContent(),
                page.getTotalPages(),
                page.getTotalElements(),
                page.getSize(),
                page.getNumber()
        );
    }
}
