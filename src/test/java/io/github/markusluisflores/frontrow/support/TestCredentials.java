package io.github.markusluisflores.frontrow.support;

import io.github.markusluisflores.frontrow.security.Credentials;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Every test context's users and agent tokens, generated once per JVM, so no raw password or token is committed (spec
 * §12; plan 4A decision 5). The nested EnvironmentPostProcessor, registered in META-INF/spring.factories, adds their
 * hashes to every SpringApplication the tests start.
 */
public final class TestCredentials {

    /** Each user's roles. alice and carol hold agent tokens; carol is also an ORGANISER. */
    public static final Map<String, List<String>> USERS = Map.of(
            "alice", List.of("BUYER"),
            "bob", List.of("BUYER"),
            "carol", List.of("BUYER", "ORGANISER"),
            "olivia", List.of("ORGANISER"),
            "adam", List.of("ADMIN"));

    public static final List<String> TOKEN_HOLDERS = List.of("alice", "carol");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> PASSWORDS = new HashMap<>();
    private static final Map<String, String> TOKENS = new HashMap<>();

    static {
        USERS.keySet().forEach(user -> PASSWORDS.put(user, randomValue()));
        TOKEN_HOLDERS.forEach(user -> TOKENS.put(user, randomValue()));
    }

    private TestCredentials() {}

    public static String password(String user) {
        return PASSWORDS.get(user);
    }

    public static String token(String user) {
        return TOKENS.get(user);
    }

    private static String randomValue() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static final class Environment implements EnvironmentPostProcessor {

        @Override
        public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
            // Cost 4, the minimum: these hashes protect nothing, and every test context computes them.
            BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
            Map<String, Object> properties = new HashMap<>();
            List<String> users = USERS.keySet().stream().sorted().toList();
            for (int i = 0; i < users.size(); i++) {
                String user = users.get(i);
                String prefix = "frontrow.security.users[" + i + "].";
                properties.put(prefix + "username", user);
                properties.put(prefix + "password-hash", "{bcrypt}" + bcrypt.encode(password(user)));
                properties.put(prefix + "roles", String.join(",", USERS.get(user)));
            }
            for (int i = 0; i < TOKEN_HOLDERS.size(); i++) {
                String user = TOKEN_HOLDERS.get(i);
                properties.put("frontrow.security.agent-tokens[" + i + "].sha256", Credentials.sha256Hex(token(user)));
                properties.put("frontrow.security.agent-tokens[" + i + "].username", user);
            }
            environment.getPropertySources().addLast(new MapPropertySource("testCredentials", properties));
        }
    }
}
