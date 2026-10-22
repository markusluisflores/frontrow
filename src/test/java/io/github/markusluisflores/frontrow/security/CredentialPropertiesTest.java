package io.github.markusluisflores.frontrow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Runs the real @Validated binding and the cross-field checks, with no database and no full application context. */
class CredentialPropertiesTest {

    private static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("secret");
    private static final String TOKEN_HASH = Credentials.sha256Hex("token");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CredentialsConfig.class)
            .withPropertyValues(
                    "frontrow.security.users[0].username=alice",
                    "frontrow.security.users[0].password-hash=" + HASH,
                    "frontrow.security.users[0].roles=BUYER",
                    "frontrow.security.users[1].username=olivia",
                    "frontrow.security.users[1].password-hash=" + HASH,
                    "frontrow.security.users[1].roles=ORGANISER",
                    "frontrow.security.agent-tokens[0].sha256=" + TOKEN_HASH,
                    "frontrow.security.agent-tokens[0].username=alice");

    @Test
    void aValidSetBindsAndAuthenticates() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Credentials credentials = context.getBean(Credentials.class);
            assertThat(credentials.ownerOfToken("token")).contains("alice");
            assertThat(credentials.ownerOfToken("Token")).isEmpty();
            UserDetails alice = credentials.userDetailsService().loadUserByUsername("alice");
            assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                            .matches("secret", alice.getPassword()))
                    .isTrue();
            assertThat(alice.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_BUYER");
        });
    }

    @Test
    void noUsersAtAllIsRejected() {
        new ApplicationContextRunner()
                .withUserConfiguration(CredentialsConfig.class)
                .run(context -> assertRejected(context, "users"));
    }

    @Test
    void aUsernameThatCouldAliasAnotherIsRejected() {
        // Uppercase, a Cyrillic look-alike, U+0000 and an unpaired surrogate (PR #18 thread 4296749409).
        for (String username : new String[] {"Alice", "аlice", "al\u0000ice", "al\uD800ice", "-alice"}) {
            runner.withPropertyValues("frontrow.security.users[0].username=" + username)
                    .run(context -> assertRejected(context, "users[0].username"));
        }
    }

    @Test
    void aPasswordThatIsNotABcryptHashIsRejected() {
        for (String hash : new String[] {"{noop}secret", "secret", HASH.substring("{bcrypt}".length())}) {
            runner.withPropertyValues("frontrow.security.users[0].password-hash=" + hash)
                    .run(context -> assertRejected(context, "users[0].passwordHash"));
        }
    }

    @Test
    void aUserWithNoRoleIsRejected() {
        runner.withPropertyValues("frontrow.security.users[1].roles=")
                .run(context -> assertRejected(context, "users[1].roles"));
    }

    @Test
    void aMalformedTokenHashIsRejected() {
        runner.withPropertyValues("frontrow.security.agent-tokens[0].sha256=" + TOKEN_HASH.toUpperCase())
                .run(context -> assertRejected(context, "agentTokens[0].sha256"));
    }

    @Test
    void aTokenForAMissingUserOrANonBuyerIsRejected() {
        for (String username : new String[] {"mallory", "olivia"}) {
            runner.withPropertyValues("frontrow.security.agent-tokens[0].username=" + username)
                    .run(context -> assertStartupFails(context, "tokens are issued only to BUYER users"));
        }
    }

    @Test
    void aDuplicateUsernameOrTokenIsRejected() {
        runner.withPropertyValues("frontrow.security.users[1].username=alice")
                .run(context -> assertStartupFails(context, "duplicate username"));
        runner.withPropertyValues(
                        "frontrow.security.agent-tokens[1].sha256=" + TOKEN_HASH,
                        "frontrow.security.agent-tokens[1].username=alice")
                .run(context -> assertStartupFails(context, "duplicate token hash"));
    }

    private static void assertRejected(AssertableApplicationContext context, String field) {
        assertThat(context).hasFailed();
        Optional<BindValidationException> validation = bindValidationCause(context.getStartupFailure());
        assertThat(validation).isPresent();
        assertThat(validation.get().getValidationErrors().getAllErrors())
                .anySatisfy(error -> assertThat(error.toString()).contains(field));
    }

    private static void assertStartupFails(AssertableApplicationContext context, String message) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(message);
    }

    private static Optional<BindValidationException> bindValidationCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BindValidationException validation) {
                return Optional.of(validation);
            }
        }
        return Optional.empty();
    }
}
