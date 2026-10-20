package com.taskapp.common.error;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Factory for the app's ProblemDetail shape: RFC 7807 fields plus {@code code} and optional {@code errors}. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        // Spring 7 / Jackson 3 no longer writes the default type, but the contract shows "type":"about:blank".
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail, List<ApiFieldError> errors) {
        ProblemDetail problem = of(status, code, detail);
        problem.setProperty("errors", errors);
        return problem;
    }

    public static ProblemDetail withInstance(ProblemDetail problem, String path) {
        problem.setInstance(URI.create(path));
        return problem;
    }
}
