package io.github.markusluisflores.frontrow.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * The validated credential set. Startup fails here on any rule the per-field constraints cannot express: a duplicate
 * username or token, or a token for a user who is missing or lacks BUYER — tokens are issued only to BUYER users (spec
 * §3).
 */
public final class Credentials {

    private final Map<String, CredentialProperties.User> users;
    private final Map<String, String> tokenOwners;

    private Credentials(Map<String, CredentialProperties.User> users, Map<String, String> tokenOwners) {
        this.users = users;
        this.tokenOwners = tokenOwners;
    }

    public static Credentials from(CredentialProperties properties) {
        Map<String, CredentialProperties.User> users = new HashMap<>();
        for (CredentialProperties.User user : properties.users()) {
            if (users.putIfAbsent(user.username(), user) != null) {
                throw new IllegalStateException("frontrow.security.users: duplicate username " + user.username());
            }
        }
        Map<String, String> tokenOwners = new HashMap<>();
        for (CredentialProperties.AgentToken token : properties.agentTokens()) {
            CredentialProperties.User user = users.get(token.username());
            if (user == null || !user.roles().contains(Role.BUYER)) {
                throw new IllegalStateException(
                        "frontrow.security.agent-tokens: tokens are issued only to BUYER users, not "
                                + token.username());
            }
            if (tokenOwners.putIfAbsent(token.sha256(), token.username()) != null) {
                throw new IllegalStateException("frontrow.security.agent-tokens: duplicate token hash");
            }
        }
        return new Credentials(Map.copyOf(users), Map.copyOf(tokenOwners));
    }

    /** The Basic users, with Spring's ROLE_ prefix on each role. */
    public UserDetailsService userDetailsService() {
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        users.values()
                .forEach(user -> manager.createUser(User.withUsername(user.username())
                        .password(user.passwordHash())
                        .roles(user.roles().stream().map(Role::name).toArray(String[]::new))
                        .build()));
        return manager;
    }

    /** The user a raw agent token acts for, if any. Only the token's SHA-256 is ever compared or stored. */
    public Optional<String> ownerOfToken(String rawToken) {
        return Optional.ofNullable(tokenOwners.get(sha256Hex(rawToken)));
    }

    public static String sha256Hex(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JDK provides SHA-256", impossible);
        }
    }
}
