package eg.mts.gsuif.filter;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void doFilter_whenHeaderPresent_usesExistingId() throws ServletException, IOException {
        String existingId = "client-req-abc-123";
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId);

        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(existingId);
            }
        };

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(existingId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_whenHeaderAbsent_generatesValidUuid() throws ServletException, IOException {
        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                String mdcId = MDC.get(CorrelationIdFilter.MDC_KEY);
                assertThat(mdcId).isNotNull();
                assertThat(UUID.fromString(mdcId)).isNotNull();
            }
        };

        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(responseHeader).isNotNull();
        assertThat(UUID.fromString(responseHeader)).isNotNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_whenHeaderBlank_generatesValidUuid() throws ServletException, IOException {
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ");

        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                String mdcId = MDC.get(CorrelationIdFilter.MDC_KEY);
                assertThat(mdcId).isNotBlank();
                assertThat(UUID.fromString(mdcId)).isNotNull();
            }
        };

        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(responseHeader).isNotBlank();
        assertThat(UUID.fromString(responseHeader)).isNotNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_whenHeaderContainsCRLF_generatesValidUuid() throws ServletException, IOException {
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "valid\r\ninjected");

        MockFilterChain filterChain = new MockFilterChain();
        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(responseHeader).isNotBlank();
        assertThat(responseHeader).doesNotContain("\r", "\n", "valid");
        assertThat(UUID.fromString(responseHeader)).isNotNull();
    }

    @Test
    void doFilter_whenHeaderContainsInvalidChars_generatesValidUuid() throws ServletException, IOException {
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "invalid!@#");

        MockFilterChain filterChain = new MockFilterChain();
        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(responseHeader).isNotBlank();
        assertThat(responseHeader).doesNotContain("invalid!@#");
        assertThat(UUID.fromString(responseHeader)).isNotNull();
    }

    @Test
    void doFilter_whenHeaderIsOversized_generatesValidUuid() throws ServletException, IOException {
        String oversized = "a".repeat(65);
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, oversized);

        MockFilterChain filterChain = new MockFilterChain();
        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(responseHeader).isNotBlank();
        assertThat(responseHeader).isNotEqualTo(oversized);
        assertThat(UUID.fromString(responseHeader)).isNotNull();
    }

    @Test
    void doFilter_cleansUpMdc_evenWhenChainThrows() {
        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotNull();
                throw new RuntimeException("Simulated downstream failure");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated downstream failure");

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_whenNoHeader_generatesUuidAndReusesOnAsyncDispatch() throws ServletException, IOException {
        // First dispatch: REQUEST (default)
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(request, response, firstChain);

        String generatedId = (String) request.getAttribute(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generatedId).isNotNull();

        // Simulate ASYNC redispatch (MDC should be clear before it starts)
        MDC.clear();
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);

        MockFilterChain asyncChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(generatedId);
            }
        };

        filter.doFilter(request, response, asyncChain);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_whenNoHeader_generatesUuidAndReusesOnErrorDispatch() throws ServletException, IOException {
        // First dispatch: REQUEST
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(request, response, firstChain);

        String generatedId = (String) request.getAttribute(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generatedId).isNotNull();

        // Simulate ERROR redispatch
        MDC.clear();
        request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);

        MockFilterChain errorChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(generatedId);
            }
        };

        filter.doFilter(request, response, errorChain);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_whenValidHeader_reusesIdOnErrorDispatch() throws ServletException, IOException {
        String clientHeader = "client-valid-id";
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, clientHeader);

        // First dispatch: REQUEST
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(request, response, firstChain);

        assertThat(request.getAttribute(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(clientHeader);

        // Simulate ERROR redispatch
        MDC.clear();
        request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);

        MockFilterChain errorChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(clientHeader);
            }
        };

        filter.doFilter(request, response, errorChain);
    }
}
