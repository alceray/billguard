package dev.billguard.coreapi.auth;

import dev.billguard.coreapi.auth.dto.UpsertUserRequest;
import dev.billguard.coreapi.auth.dto.UserResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/me")
    UserResponse upsert(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody UpsertUserRequest request
    ) {
        return userService.upsert(jwt.getSubject(), request);
    }

    @GetMapping("/me")
    UserResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
        return userService.findBySubject(jwt.getSubject());
    }
}
