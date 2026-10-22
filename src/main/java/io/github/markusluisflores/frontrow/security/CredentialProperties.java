package io.github.markusluisflores.frontrow.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Demo-grade credentials (spec §9): in-memory Basic users and static agent tokens, stored only as hashes. Nothing
 * usable is committed: scripts/demo-credentials.sh writes them to a gitignored config/application-demo.yml, and tests
 * generate their own (plan 4A decision 5). Cross-field rules are checked by {@link Credentials}.
 */
@Validated
@ConfigurationProperties(prefix = "frontrow.security")
public record CredentialProperties(@NotEmpty List<@Valid User> users, List<@Valid AgentToken> agentTokens) {

    /**
     * A username is the owner string for both credentials (plan 4A decision 3). Lowercase ASCII only, so no two
     * principals can alias: no case or Unicode look-alikes, no U+0000 and no unpaired surrogate (PR #18 thread
     * 4296749409).
     */
    public static final String USERNAME_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$";

    /** Spring's delegating form of a bcrypt hash; nothing else is accepted, so a {noop} password cannot slip in. */
    public static final String BCRYPT_PATTERN = "^\\{bcrypt\\}\\$2[aby]?\\$\\d\\d\\$[./0-9A-Za-z]{53}$";

    public static final String SHA256_HEX_PATTERN = "^[0-9a-f]{64}$";

    /** Immutable copies. An absent list binds as empty, so a missing users list fails @NotEmpty. */
    public CredentialProperties {
        users = users == null ? List.of() : List.copyOf(users);
        agentTokens = agentTokens == null ? List.of() : List.copyOf(agentTokens);
    }

    public record User(
            @NotNull @Pattern(regexp = USERNAME_PATTERN) String username,
            @NotNull @Pattern(regexp = BCRYPT_PATTERN) String passwordHash,
            @NotEmpty Set<Role> roles) {

        public User {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }

    /** An agent token, by the SHA-256 of its raw value, and the user it acts for. */
    public record AgentToken(
            @NotNull @Pattern(regexp = SHA256_HEX_PATTERN) String sha256,
            @NotNull @Pattern(regexp = USERNAME_PATTERN) String username) {}
}
