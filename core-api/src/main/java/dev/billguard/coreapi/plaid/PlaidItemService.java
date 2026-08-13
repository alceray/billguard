package dev.billguard.coreapi.plaid;

import java.util.List;

import dev.billguard.coreapi.auth.User;
import dev.billguard.coreapi.auth.UserRepository;
import dev.billguard.coreapi.common.HttpException;
import dev.billguard.coreapi.config.TokenCipher;
import dev.billguard.coreapi.plaid.dto.AccountResponse;
import dev.billguard.coreapi.plaid.dto.ExchangeRequest;
import dev.billguard.coreapi.plaid.dto.ExchangeResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlaidItemService {
    private final UserRepository userRepository;
    private final PlaidGateway plaidGateway;
    private final TokenCipher tokenCipher;
    private final PlaidItemPersistenceService persistenceService;

    public PlaidItemService(
        UserRepository userRepository,
        PlaidGateway plaidGateway,
        TokenCipher tokenCipher,
        PlaidItemPersistenceService persistenceService
    ) {
        this.userRepository = userRepository;
        this.plaidGateway = plaidGateway;
        this.tokenCipher = tokenCipher;
        this.persistenceService = persistenceService;
    }

    public ExchangeResponse exchange(String subject, ExchangeRequest request) {
        User user = userRepository.findByAuth0Sub(subject)
            .orElseThrow(() -> new HttpException(HttpStatus.NOT_FOUND, "User not found"));
        try {
            var exchange = plaidGateway.exchangePublicToken(request.publicToken());
            var accounts = plaidGateway.getAccounts(exchange.getAccessToken()).getAccounts();
            String encryptedToken = tokenCipher.encrypt(exchange.getAccessToken());
            List<AccountResponse> persisted = persistenceService.persistExchange(
                user.getId(), exchange.getItemId(), encryptedToken,
                request.institutionId(), request.institutionName(), accounts);
            return new ExchangeResponse(exchange.getItemId(), request.institutionName(), persisted);
        } catch (PlaidGatewayException exception) {
            throw PlaidHttpErrors.upstream(exception);
        }
    }
}
