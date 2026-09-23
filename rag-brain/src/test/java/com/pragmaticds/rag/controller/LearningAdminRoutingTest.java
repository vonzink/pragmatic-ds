package com.pragmaticds.rag.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the safety invariant that every learning admin route sits under
 * /api/ai/admin, so AdminApiKeyFilter (which gates paths starting with that
 * prefix) protects them. If someone moves a route out of the prefix, this fails.
 */
class LearningAdminRoutingTest {

    private static final String ADMIN_PREFIX = "/api/ai/admin";

    @Test
    void adminLearningControllerBaseIsUnderAdminPrefix() {
        RequestMapping rm = AdminLearningController.class.getAnnotation(RequestMapping.class);
        assertTrue(rm.value().length > 0, "AdminLearningController must declare a base path");
        assertTrue(rm.value()[0].startsWith(ADMIN_PREFIX),
                "AdminLearningController base '" + rm.value()[0] + "' must be under " + ADMIN_PREFIX);
    }

    @Test
    void everyLearningRouteResolvesUnderAdminPrefix() throws Exception {
        String base = AdminLearningController.class.getAnnotation(RequestMapping.class).value()[0];
        for (String sub : new String[]{"pending", "weights", "reset", "approve", "reject"}) {
            assertTrue((base).startsWith(ADMIN_PREFIX),
                    "route group '" + sub + "' base '" + base + "' must be under " + ADMIN_PREFIX);
        }
    }

    @Test
    void brainLearningToggleIsUnderAdminPrefix() throws Exception {
        RequestMapping rm = BrainAdminController.class.getAnnotation(RequestMapping.class);
        String base = rm.value()[0];
        Method toggle = BrainAdminController.class.getMethod("learning", java.util.UUID.class, boolean.class);
        PostMapping pm = toggle.getAnnotation(PostMapping.class);
        String full = base + pm.value()[0];
        assertTrue(full.startsWith(ADMIN_PREFIX),
                "brain learning toggle '" + full + "' must be under " + ADMIN_PREFIX);
    }

    @Test
    void pendingIsAGetAndResetIsAPost() throws Exception {
        Method pending = AdminLearningController.class.getMethod("pending", String.class);
        Method reset = AdminLearningController.class.getMethod("reset", String.class);
        assertTrue(pending.isAnnotationPresent(GetMapping.class), "pending must be a GET");
        assertTrue(reset.isAnnotationPresent(PostMapping.class), "reset must be a POST");
    }
}
