package dev.billguard.coreapi.plaid;

public final class PlaidGatewayException extends RuntimeException {
    private final String code;

    public PlaidGatewayException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PlaidGatewayException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
