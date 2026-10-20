package com.taskapp.security.google;

import java.io.IOException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;
import com.taskapp.common.error.ErrorCode;
import com.taskapp.common.error.Problems;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;
import com.taskapp.security.ratelimit.GoogleRateLimiter;
import com.taskapp.security.ratelimit.RateLimitedException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Everything under {@code /api/auth/oauth2/} passes here first: after the CSRF check and the session check
 * ({@code SessionUserRefreshFilter}), before Spring Security's OAuth2 filters (contract 0.8.0).
 * <ul>
 * <li>Google sign-in off, or any path other than the start and the callback: 404 {@code NOT_FOUND}.</li>
 * <li>Start ({@code POST /api/auth/oauth2/authorization/google}, CSRF already checked; any other method 405), in
 * order: per-IP limit, {@code intent} ({@code login} by default, or {@code connect}), a signed-in session for a
 * connect (the remember-me cookie is not used here), Google's configuration loadable. Each failure is a 302 to the
 * frontend; otherwise Spring's redirect filter sends the browser to Google.</li>
 * <li>Callback ({@code GET /api/auth/oauth2/callback/google}; other methods 405): per-IP limit, then Spring's login
 * filter. Remembers who is signed in before it runs, and turns any unexpected error into the failure redirect.</li>
 * </ul>
 */
public final class GoogleFlowGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GoogleFlowGuardFilter.class);

    private static final String INTENT = GoogleFlowGuardFilter.class.getName() + ".INTENT";
    private static final String USER_ID = GoogleFlowGuardFilter.class.getName() + ".USER_ID";
    private static final String SIGNED_IN_USER = GoogleFlowGuardFilter.class.getName() + ".SIGNED_IN_USER";

    private static final RequestMatcher FLOW = PathPatternRequestMatcher.withDefaults()
            .matcher(GoogleSignIn.FLOW_PREFIX + "**");
    private static final RequestMatcher START = PathPatternRequestMatcher.withDefaults()
            .matcher(GoogleSignIn.START_PATH);
    private static final RequestMatcher CALLBACK = PathPatternRequestMatcher.withDefaults()
            .matcher(GoogleSignIn.CALLBACK_PATH);

    private final GoogleSignIn google;
    private final Supplier<GoogleRateLimiter> rateLimiter;
    private final PendingAuthorizationRequests pendingRequests;
    private final GoogleCallbackHandler callbackHandler;
    private final ObjectMapper objectMapper;

    GoogleFlowGuardFilter(GoogleSignIn google, Supplier<GoogleRateLimiter> rateLimiter,
            PendingAuthorizationRequests pendingRequests, GoogleCallbackHandler callbackHandler,
            ObjectMapper objectMapper) {
        this.google = google;
        this.rateLimiter = rateLimiter;
        this.pendingRequests = pendingRequests;
        this.callbackHandler = callbackHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !FLOW.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean start = START.matches(request);
        boolean callback = CALLBACK.matches(request);
        if (!google.enabled() || (!start && !callback)) {
            writeProblem(request, response, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Not found");
            return;
        }
        if (start) {
            if (!HttpMethod.POST.matches(request.getMethod())) {
                methodNotAllowed(request, response, HttpMethod.POST);
                return;
            }
            start(request, response, chain);
        } else {
            if (!HttpMethod.GET.matches(request.getMethod())) {
                methodNotAllowed(request, response, HttpMethod.GET);
                return;
            }
            callback(request, response, chain);
        }
    }

    private void start(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        GoogleFlowIntent intent = GoogleFlowIntent.fromForm(request.getParameter("intent"));
        AuthenticatedUser user = currentUser();
        String email = user == null ? null : user.email();
        if (!rateLimiter.get().tryAcquireStart(clientIp(request))) {
            SecurityEventLogger.log(SecurityEvent.RATE_LIMITED, "limit",
                    RateLimitedException.Limit.GOOGLE_START_IP.logValue(), request, email);
            redirect(response, (intent == null ? GoogleFlowIntent.LOGIN : intent).failure());
            return;
        }
        if (intent == null) {
            startFailed(request, response, null, "invalid_intent", email, OAuthResult.GOOGLE_FAILED);
            return;
        }
        if (intent == GoogleFlowIntent.CONNECT && user == null) {
            startFailed(request, response, intent, "no_session", null, OAuthResult.GOOGLE_FAILED);
            return;
        }
        try {
            // Loads Google's OpenID configuration on first use (network); cached once loaded.
            google.registrations().findByRegistrationId(GoogleSignIn.REGISTRATION_ID);
        } catch (RuntimeException e) {
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            log.warn("Google sign-in unavailable: its OpenID configuration could not be loaded ({}: {})",
                    root.getClass().getName(), root.getMessage());
            startFailed(request, response, intent, "provider_unavailable", email, intent.failure());
            return;
        }
        request.setAttribute(INTENT, intent);
        if (intent == GoogleFlowIntent.CONNECT) {
            request.setAttribute(USER_ID, user.id());
        }
        chain.doFilter(request, response);
    }

    private void callback(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthenticatedUser user = currentUser();
        if (user != null) {
            request.setAttribute(SIGNED_IN_USER, user);
        }
        if (!rateLimiter.get().tryAcquireCallback(clientIp(request))) {
            SecurityEventLogger.log(SecurityEvent.RATE_LIMITED, "limit",
                    RateLimitedException.Limit.GOOGLE_CALLBACK_IP.logValue(), request,
                    user == null ? null : user.email());
            // Ends the flow: the pending request (if this callback's state matches it) is dropped.
            OAuth2AuthorizationRequest pending = pendingRequests.removeAuthorizationRequest(request, response);
            callbackHandler.rateLimited(request, response, pending);
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (response.isCommitted()) {
                throw e;
            }
            response.resetBuffer();
            callbackHandler.serverError(request, response, PendingAuthorizationRequests.removed(request), e);
        }
    }

    private void startFailed(HttpServletRequest request, HttpServletResponse response, GoogleFlowIntent intent,
            String reason, String email, OAuthResult route) throws IOException {
        GoogleCallbackHandler.audit(SecurityEvent.GOOGLE_LOGIN_FAILURE, request, email,
                "intent", intent == null ? null : intent.value(), "reason", reason);
        redirect(response, route);
    }

    private void redirect(HttpServletResponse response, OAuthResult route) throws IOException {
        response.sendRedirect(google.redirectUrl(route));
    }

    private void methodNotAllowed(HttpServletRequest request, HttpServletResponse response, HttpMethod allowed)
            throws IOException {
        response.setHeader(HttpHeaders.ALLOW, allowed.name());
        // Same code as Spring MVC's own 405s (GlobalExceptionHandler).
        writeProblem(request, response, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.MALFORMED_REQUEST,
                "Method '" + request.getMethod() + "' is not supported.");
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String code, String detail) throws IOException {
        ProblemDetail problem = Problems.withInstance(Problems.of(status, code, detail), request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** The signed-in user (after the session check), or null. */
    private static AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    /** Same client address as every other per-IP limit (resolved through trusted proxies only). */
    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }

    /** The intent of a start this filter accepted, or null. */
    static GoogleFlowIntent acceptedIntent(HttpServletRequest request) {
        return request.getAttribute(INTENT) instanceof GoogleFlowIntent intent ? intent : null;
    }

    /** For an accepted connect: the signed-in user's id. */
    static Long connectingUserId(HttpServletRequest request) {
        return request.getAttribute(USER_ID) instanceof Long id ? id : null;
    }

    /** On the callback: who was signed in before Spring's login filter ran, or null. */
    static AuthenticatedUser signedInUser(HttpServletRequest request) {
        return request.getAttribute(SIGNED_IN_USER) instanceof AuthenticatedUser user ? user : null;
    }
}
