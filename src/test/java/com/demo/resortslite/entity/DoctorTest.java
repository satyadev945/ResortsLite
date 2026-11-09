package com.demo.resortslite.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Doctor} entity.
 */
class DoctorTest {

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        doctor = new Doctor();
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    @Test
    void defaultConstructor_createsInstance() {
        Doctor d = new Doctor();
        assertNotNull(d);
    }

    // ── docId ────────────────────────────────────────────────────────────────

    @Test
    void setDocId_andGetDocId_returnsExpectedValue() {
        doctor.setDocId(101);
        assertEquals(101, doctor.getDocId());
    }

    @Test
    void getDocId_whenNotSet_returnsNull() {
        assertNull(doctor.getDocId());
    }

    @Test
    void setDocId_withZero_returnsZero() {
        doctor.setDocId(0);
        assertEquals(0, doctor.getDocId());
    }

    @Test
    void setDocId_withNegativeValue_returnsNegativeValue() {
        doctor.setDocId(-5);
        assertEquals(-5, doctor.getDocId());
    }

    @Test
    void setDocId_withMaxInteger_returnsMaxInteger() {
        doctor.setDocId(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, doctor.getDocId());
    }

    @Test
    void setDocId_withNull_returnsNull() {
        doctor.setDocId(null);
        assertNull(doctor.getDocId());
    }

    // ── docName ──────────────────────────────────────────────────────────────

    @Test
    void setDocName_andGetDocName_returnsExpectedValue() {
        doctor.setDocName("Dr. Alice");
        assertEquals("Dr. Alice", doctor.getDocName());
    }

    @Test
    void getDocName_whenNotSet_returnsNull() {
        assertNull(doctor.getDocName());
    }

    @Test
    void setDocName_withEmptyString_returnsEmptyString() {
        doctor.setDocName("");
        assertEquals("", doctor.getDocName());
    }

    @Test
    void setDocName_withNull_returnsNull() {
        doctor.setDocName(null);
        assertNull(doctor.getDocName());
    }

    @Test
    void setDocName_withSpecialCharacters_returnsSpecialCharacters() {
        doctor.setDocName("Dr. O'Brien-Smith");
        assertEquals("Dr. O'Brien-Smith", doctor.getDocName());
    }

    // ── specialization ───────────────────────────────────────────────────────

    @Test
    void setSpecialization_andGetSpecialization_returnsExpectedValue() {
        doctor.setSpecialization("Cardiology");
        assertEquals("Cardiology", doctor.getSpecialization());
    }

    @Test
    void getSpecialization_whenNotSet_returnsNull() {
        assertNull(doctor.getSpecialization());
    }

    @Test
    void setSpecialization_withNull_returnsNull() {
        doctor.setSpecialization(null);
        assertNull(doctor.getSpecialization());
    }

    @Test
    void setSpecialization_withEmptyString_returnsEmptyString() {
        doctor.setSpecialization("");
        assertEquals("", doctor.getSpecialization());
    }

    // ── income ───────────────────────────────────────────────────────────────

    @Test
    void setIncome_andGetIncome_returnsExpectedValue() {
        doctor.setIncome(75000.50);
        assertEquals(75000.50, doctor.getIncome());
    }

    @Test
    void getIncome_whenNotSet_returnsNull() {
        assertNull(doctor.getIncome());
    }

    @Test
    void setIncome_withZero_returnsZero() {
        doctor.setIncome(0.0);
        assertEquals(0.0, doctor.getIncome());
    }

    @Test
    void setIncome_withNegativeValue_returnsNegativeValue() {
        doctor.setIncome(-100.0);
        assertEquals(-100.0, doctor.getIncome());
    }

    @Test
    void setIncome_withNull_returnsNull() {
        doctor.setIncome(null);
        assertNull(doctor.getIncome());
    }

    @Test
    void setIncome_withLargeValue_returnsLargeValue() {
        doctor.setIncome(Double.MAX_VALUE);
        assertEquals(Double.MAX_VALUE, doctor.getIncome());
    }

    // ── Multiple field interactions ──────────────────────────────────────────

    @Test
    void setAllFields_andGetAllFields_returnsCorrectValues() {
        doctor.setDocId(203);
        doctor.setDocName("Dr. Bob");
        doctor.setSpecialization("Neurology");
        doctor.setIncome(120000.00);

        assertAll(
            () -> assertEquals(203, doctor.getDocId()),
            () -> assertEquals("Dr. Bob", doctor.getDocName()),
            () -> assertEquals("Neurology", doctor.getSpecialization()),
            () -> assertEquals(120000.00, doctor.getIncome())
        );
    }

    @Test
    void setDocId_overwrite_returnsNewValue() {
        doctor.setDocId(1);
        doctor.setDocId(2);
        assertEquals(2, doctor.getDocId());
    }

    @Test
    void setDocName_overwrite_returnsNewValue() {
        doctor.setDocName("Dr. First");
        doctor.setDocName("Dr. Second");
        assertEquals("Dr. Second", doctor.getDocName());
    }
}
