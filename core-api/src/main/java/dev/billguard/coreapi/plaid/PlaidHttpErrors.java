package dev.billguard.coreapi.plaid;

import dev.billguard.coreapi.common.HttpException;

import org.springframework.http.HttpStatus;

final class PlaidHttpErrors {
    private PlaidHttpErrors() {
    }

    static HttpException upstream(PlaidGatewayException exception) {
        return new HttpException(HttpStatus.BAD_GATEWAY, "Bank connection provider request failed", exception.code());
    }
}
