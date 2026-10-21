package io.github.markusluisflores.frontrow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The generated file is valid configuration, and the raw values shown once are the ones its hashes accept. */
class DemoCredentialsTest {

    private static final Pattern PASSWORD_LINE = Pattern.compile("  (\\S+) \\(\\w+\\) password: (\\S+)");
    private static final Pattern TOKEN_LINE = Pattern.compile("  (\\S+)'s agent token: (\\S+)");

    @Test
    void theGeneratedYamlBindsAndAcceptsTheRawValuesShownOnce() throws IOException {
        DemoCredentials.Generated generated = DemoCredentials.generate(new SecureRandom());
        var source = new YamlPropertySourceLoader()
                .load("demo", new ByteArrayResource(generated.yaml().getBytes(StandardCharsets.UTF_8)))
                .getFirst();

        new ApplicationContextRunner()
                .withUserConfiguration(CredentialsConfig.class)
                .withInitializer(
                        context -> context.getEnvironment().getPropertySources().addFirst(source))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Credentials credentials = context.getBean(Credentials.class);
                    PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
                    Map<String, String> passwords = matches(PASSWORD_LINE, generated.rawValues());
                    assertThat(passwords).containsOnlyKeys(DemoCredentials.USERS.keySet());
                    passwords.forEach((user, password) -> assertThat(encoder.matches(
                                    password,
                                    credentials
                                            .userDetailsService()
                                            .loadUserByUsername(user)
                                            .getPassword()))
                            .isTrue());
                    Map<String, String> tokens = matches(TOKEN_LINE, generated.rawValues());
                    assertThat(tokens).containsOnlyKeys(DemoCredentials.TOKEN_HOLDER);
                    assertThat(credentials.ownerOfToken(tokens.get(DemoCredentials.TOKEN_HOLDER)))
                            .contains(DemoCredentials.TOKEN_HOLDER);
                });
        assertThat(generated.yaml())
                .doesNotContain(matches(PASSWORD_LINE, generated.rawValues()).values());
    }

    private static Map<String, String> matches(Pattern pattern, String text) {
        Map<String, String> found = new HashMap<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.put(matcher.group(1), matcher.group(2));
        }
        return found;
    }
}
