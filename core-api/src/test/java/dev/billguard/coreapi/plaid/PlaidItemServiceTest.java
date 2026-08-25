package dev.billguard.coreapi.plaid;

import java.util.Optional;
import java.util.UUID;

import dev.billguard.coreapi.auth.User;
import dev.billguard.coreapi.auth.UserRepository;
import dev.billguard.coreapi.common.HttpException;
import dev.billguard.coreapi.config.TokenCipher;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaidItemServiceTest {
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PlaidGateway plaidGateway = mock(PlaidGateway.class);
    private final TokenCipher tokenCipher = mock(TokenCipher.class);
    private final PlaidItemRepository itemRepository = mock(PlaidItemRepository.class);
    private final PlaidItemPersistenceService persistenceService = mock(PlaidItemPersistenceService.class);
    private final PlaidItemService service = new PlaidItemService(
        userRepository, plaidGateway, tokenCipher, itemRepository, persistenceService);

    @Test
    void disconnectRevokesProviderAccessBeforeRemovingLocalData() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        User user = mock(User.class);
        PlaidItem item = mock(PlaidItem.class);
        when(user.getId()).thenReturn(userId);
        when(item.getId()).thenReturn(itemId);
        when(item.getUserId()).thenReturn(userId);
        when(item.getPlaidItemId()).thenReturn("plaid-item");
        when(item.getAccessTokenEnc()).thenReturn("encrypted-token");
        when(userRepository.findByAuth0Sub("auth0|user-a")).thenReturn(Optional.of(user));
        when(itemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.of(item));
        when(tokenCipher.decrypt("encrypted-token")).thenReturn("access-token");

        service.disconnect("auth0|user-a", itemId);

        InOrder calls = inOrder(plaidGateway, persistenceService);
        calls.verify(plaidGateway).removeItem("access-token");
        calls.verify(persistenceService).removeItem(userId, itemId, "plaid-item");
    }

    @Test
    void disconnectKeepsLocalDataWhenProviderRevocationFails() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        User user = mock(User.class);
        PlaidItem item = mock(PlaidItem.class);
        when(user.getId()).thenReturn(userId);
        when(item.getAccessTokenEnc()).thenReturn("encrypted-token");
        when(userRepository.findByAuth0Sub("auth0|user-a")).thenReturn(Optional.of(user));
        when(itemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.of(item));
        when(tokenCipher.decrypt("encrypted-token")).thenReturn("access-token");
        doThrow(new PlaidGatewayException("PLAID_UNAVAILABLE", "Unavailable"))
            .when(plaidGateway).removeItem("access-token");

        HttpException exception = assertThrows(
            HttpException.class, () -> service.disconnect("auth0|user-a", itemId));
        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY);

        verify(persistenceService, never()).removeItem(userId, itemId, "plaid-item");
    }
}
