package dev.billguard.coreapi.plaid;

import dev.billguard.coreapi.auth.User;
import dev.billguard.coreapi.auth.UserRepository;
import dev.billguard.coreapi.common.HttpException;
import dev.billguard.coreapi.plaid.dto.LinkTokenResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlaidLinkService {
    private final UserRepository userRepository;
    private final PlaidGateway plaidGateway;

    public PlaidLinkService(UserRepository userRepository, PlaidGateway plaidGateway) {
        this.userRepository = userRepository;
        this.plaidGateway = plaidGateway;
    }

    public LinkTokenResponse createLinkToken(String subject) {
        User user = userRepository.findByAuth0Sub(subject)
            .orElseThrow(() -> new HttpException(HttpStatus.NOT_FOUND, "User not found"));
        try {
            var response = plaidGateway.createLinkToken(user.getId());
            return new LinkTokenResponse(response.getLinkToken(), response.getExpiration());
        } catch (PlaidGatewayException exception) {
            throw PlaidHttpErrors.upstream(exception);
        }
    }
}
