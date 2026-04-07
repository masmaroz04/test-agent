package com.example.test_agent.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("@auth0Properties.getAllowedEmails().get(0) == authentication.principal.claims['email']")
    public List<User> getUsers() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Jwt jwt = (Jwt) auth.getPrincipal();
        log.info("[/api/v1/users] GET all — caller={}", jwt.getClaimAsString("email"));
        return userService.getAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@auth0Properties.getAllowedEmails().get(0) == authentication.principal.claims['email']")
    public User getUserById(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        log.info("[/api/v1/users/{}] GET by id — caller={}", id, jwt.getSubject());
        return userService.getById(id);
    }

    // เรียกจาก K8s CronJob — ไม่ต้องการ auth (internal call จาก cluster)
    @PostMapping("/batch/update-names")
    public String batchUpdateNames() {
        int seq = userService.updateAllFullNames();
        log.info("[/api/v1/users/batch/update-names] POST — updated to xxxx{}", String.format("%04d", seq));
        return "updated to xxxx" + String.format("%04d", seq);
    }
}
