package hyshweb.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordsTest {
    @Test
    void verifiesGeneratedSha256Password() {
        String hashed = Passwords.hash("secret");

        assertTrue(hashed.startsWith("sha256:"));
        assertTrue(Passwords.verify("secret", hashed));
        assertFalse(Passwords.verify("wrong", hashed));
    }

    @Test
    void verifiesLegacyPlaintextPassword() {
        assertTrue(Passwords.verify("legacy", "legacy"));
        assertFalse(Passwords.verify("bad", "legacy"));
    }

    @Test
    void rejectsBlankOrMissingValues() {
        assertFalse(Passwords.verify("", "legacy"));
        assertFalse(Passwords.verify("secret", ""));
        assertFalse(Passwords.verify(null, "legacy"));
        assertFalse(Passwords.verify("secret", null));
    }
}
