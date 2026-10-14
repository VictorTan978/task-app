package com.taskapp.security;

import java.io.IOException;
import java.util.function.Supplier;

import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring's remember-me filter at its standard position (after CSRF and {@link SessionUserRefreshFilter}, before
 * authorization), so a valid {@code remember-me} cookie signs the user in on ANY {@code /api/**} request without a
 * valid session, including POST/PUT/PATCH/DELETE (the CSRF check has already passed by then). The new session is
 * set up like a password login by the {@link SessionAuthenticator} (session strategy).
 * <p>
 * Skipped for {@code POST /api/auth/login}, {@code /register}, {@code /logout} and {@code /reset-password}, which
 * handle the cookie themselves, and for non-REQUEST dispatches (e.g. the error page), so a cookie is processed once per request.
 */
class SessionRememberMeAuthenticationFilter extends RememberMeAuthenticationFilter {

    private static final RequestMatcher HANDLES_COOKIE_ITSELF = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/register"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout"),
            // Signs this browser out and ends every remember-me login: signing in from the cookie first is useless.
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/reset-password"));

    SessionRememberMeAuthenticationFilter(AuthenticationManager authenticationManager,
            HashedRememberMeServices rememberMeServices, SecurityContextRepository securityContextRepository,
            Supplier<SessionAuthenticator> sessionAuthenticator) {
        super(authenticationManager, rememberMeServices);
        setSecurityContextRepository(securityContextRepository);
        setSessionAuthenticationStrategy((authentication, request, response) ->
                sessionAuthenticator.get().signInFromRememberMe(authentication, request, response));
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || (request instanceof HttpServletRequest http && HANDLES_COOKIE_ITSELF.matches(http))) {
            chain.doFilter(request, response);
            return;
        }
        super.doFilter(request, response, chain);
    }

    @Override
    protected void onSuccessfulAuthentication(HttpServletRequest request,
            HttpServletResponse response, Authentication authResult) {
        String email = authResult.getPrincipal() instanceof AuthenticatedUser user ? user.email() : null;
        SecurityEventLogger.log(SecurityEvent.REMEMBER_ME_LOGIN, request, email);
    }
}
