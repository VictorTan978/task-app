package com.taskapp.task;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.task.dto.TagResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The tags of one of the signed-in user's lists, for suggestions. {@code list} is read with {@code getParameter} (the
 * first value). Anonymous callers get 401 from the security chain.
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public List<TagResponse> list(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        return tagService.list(user.id(), request.getParameter("list"));
    }
}
