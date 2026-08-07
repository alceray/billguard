package dev.billguard.coreapi.auth;

import dev.billguard.coreapi.auth.dto.UpsertUserRequest;
import dev.billguard.coreapi.auth.dto.UserResponse;
import dev.billguard.coreapi.common.HttpException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserResponse upsert(String subject, UpsertUserRequest request) {
        UserProjection user = userRepository.upsert(subject, request.email(), request.name());
        if (user == null) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upsert user");
        }
        log.info("User upserted userId={}", user.getId());
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public UserResponse findBySubject(String subject) {
        return userRepository.findByAuth0Sub(subject)
            .map(UserResponse::from)
            .orElseThrow(() -> new HttpException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
