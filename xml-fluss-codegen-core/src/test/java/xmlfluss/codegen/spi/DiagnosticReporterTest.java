package xmlfluss.codegen.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticReporterTest {

    @Test
    void fake_capturesError_andHasErrorsBecomesTrue() {
        FakeDiagnosticReporter r = new FakeDiagnosticReporter();
        assertFalse(r.hasErrors());
        r.error("h1", "boom");
        assertTrue(r.hasErrors());
        assertEquals(1, r.entries.size());
        assertEquals(FakeDiagnosticReporter.Severity.ERROR, r.entries.get(0).severity());
        assertEquals("h1", r.entries.get(0).handle());
        assertEquals("boom", r.entries.get(0).message());
    }

    @Test
    void fake_warn_doesNotMakeHasErrorsTrue() {
        FakeDiagnosticReporter r = new FakeDiagnosticReporter();
        r.warn(null, "careful");
        assertFalse(r.hasErrors());
        assertEquals(1, r.entries.size());
        assertEquals(FakeDiagnosticReporter.Severity.WARN, r.entries.get(0).severity());
        assertNull(r.entries.get(0).handle());
    }
}
