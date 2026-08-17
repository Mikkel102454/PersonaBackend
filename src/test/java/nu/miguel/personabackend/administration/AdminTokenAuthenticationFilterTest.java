package nu.miguel.personabackend.administration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class AdminTokenAuthenticationFilterTest {
    private static final String TOKEN = "a-secure-administration-token-32-characters";

    @Test void rejectsMissingTokenAndAuthenticatesConstantBearerToken() throws Exception {
        AdminTokenAuthenticationFilter filter = new AdminTokenAuthenticationFilter(new AdministrationProperties(TOKEN));
        MockHttpServletRequest denied = new MockHttpServletRequest("GET", "/actuator/prometheus");
        denied.setRequestURI("/actuator/prometheus");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(denied, deniedResponse, (request, response) -> fail("Denied request reached actuator"));
        assertEquals(401, deniedResponse.getStatus());

        MockHttpServletRequest allowed = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        allowed.setRequestURI("/actuator/health/readiness");
        allowed.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        boolean[] invoked = {false};
        filter.doFilter(allowed, allowedResponse, (request, response) -> {
            invoked[0] = true;
            assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(value -> value.getAuthority().equals("ROLE_ADMIN")));
        });
        assertTrue(invoked[0]);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void rejectsWeakConfiguredToken() {
        assertThrows(IllegalArgumentException.class, () -> new AdministrationProperties("too-short"));
    }
}
