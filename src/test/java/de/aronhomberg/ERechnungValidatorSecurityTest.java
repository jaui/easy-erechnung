package de.aronhomberg;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ERechnungValidatorSecurityTest {
    @Test void requiresExplicitStatus() {
        assertFalse(ERechnungValidator.parseValidationResult("<validation><messages/></validation>").isValid);
    }

    @Test void acceptsValidReport() {
        assertTrue(ERechnungValidator.parseValidationResult(
                "<validation><messages/><summary status='valid'/></validation>").isValid);
    }

    @Test void rejectsDoctype() {
        assertFalse(ERechnungValidator.parseValidationResult(
                "<!DOCTYPE validation [<!ENTITY injected 'text'>]>"
                + "<validation><messages><notice>&injected;</notice></messages>"
                + "<summary status='valid'/></validation>").isValid);
    }
}
