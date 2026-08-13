package dev.billguard.coreapi.plaid;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plaid_account_id", nullable = false)
    private String plaidAccountId;

    @Column(name = "plaid_item_id", nullable = false)
    private String plaidItemId;

    @Column(nullable = false)
    private String name;

    @Column(name = "official_name")
    private String officialName;

    @Column(nullable = false)
    private String type;

    private String subtype;
    private String mask;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPlaidAccountId() {
        return plaidAccountId;
    }

    public String getPlaidItemId() {
        return plaidItemId;
    }

    public String getName() {
        return name;
    }

    public String getOfficialName() {
        return officialName;
    }

    public String getType() {
        return type;
    }

    public String getSubtype() {
        return subtype;
    }

    public String getMask() {
        return mask;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
