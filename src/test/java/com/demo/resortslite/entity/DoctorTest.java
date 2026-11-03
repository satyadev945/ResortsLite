package com.demo.resortslite.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Doctor entity class.
 * Covers all getters, setters, and field assignments.
 */
class DoctorTest {

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        doctor = new Doctor();
    }

    // ─── Constructor ───────────────────────────────────────────────────────────

    @Test
    void defaultConstructor_createsNonNullInstance() {
        Doctor d = new Doctor();
        assertNotNull(d);
    }

    // ─── docId ─────────────────────────────────────────────────────────────────

    @Test
    void setDocId_andGetDocId_returnsExpectedValue() {
        doctor.setDocId(101);
        assertEquals(101, doctor.getDocId());
    }

    @Test
    void setDocId_withNull_returnsNull() {
        doctor.setDocId(null);
        assertNull(doctor.getDocId());
    }

    @Test
    void setDocId_withNegativeValue_returnsNegativeValue() {
        doctor.setDocId(-5);
        assertEquals(-5, doctor.getDocId());
    }

    @Test
    void setDocId_withZero_returnsZero() {
        doctor.setDocId(0);
        assertEquals(0, doctor.getDocId());
    }

    @Test
    void setDocId_withMaxInteger_returnsMaxInteger() {
        doctor.setDocId(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, doctor.getDocId());
    }

    // ─── docName ───────────────────────────────────────────────────────────────

    @Test
    void setDocName_andGetDocName_returnsExpectedValue() {
        doctor.setDocName("Dr. Alice");
        assertEquals("Dr. Alice", doctor.getDocName());
    }

    @Test
    void setDocName_withNull_returnsNull() {
        doctor.setDocName(null);
        assertNull(doctor.getDocName());
    }

    @Test
    void setDocName_withEmptyString_returnsEmptyString() {
        doctor.setDocName("");
        assertEquals("", doctor.getDocName());
    }

    @Test
    void setDocName_withWhitespace_returnsWhitespace() {
        doctor.setDocName("   ");
        assertEquals("   ", doctor.getDocName());
    }

    @Test
    void setDocName_withSpecialCharacters_returnsSpecialCharacters() {
        doctor.setDocName("Dr. O'Brien-Smith");
        assertEquals("Dr. O'Brien-Smith", doctor.getDocName());
    }

    // ─── specialization ────────────────────────────────────────────────────────

    @Test
    void setSpecialization_andGetSpecialization_returnsExpectedValue() {
        doctor.setSpecialization("Cardiology");
        assertEquals("Cardiology", doctor.getSpecialization());
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

    @Test
    void setSpecialization_withLongValue_returnsLongValue() {
        String longSpec = "Neuro-Ophthalmology";
        doctor.setSpecialization(longSpec);
        assertEquals(longSpec, doctor.getSpecialization());
    }

    // ─── income ────────────────────────────────────────────────────────────────

    @Test
    void setIncome_andGetIncome_returnsExpectedValue() {
        doctor.setIncome(95000.50);
        assertEquals(95000.50, doctor.getIncome());
    }

    @Test
    void setIncome_withNull_returnsNull() {
        doctor.setIncome(null);
        assertNull(doctor.getIncome());
    }

    @Test
    void setIncome_withZero_returnsZero() {
        doctor.setIncome(0.0);
        assertEquals(0.0, doctor.getIncome());
    }

    @Test
    void setIncome_withNegativeValue_returnsNegativeValue() {
        doctor.setIncome(-1000.0);
        assertEquals(-1000.0, doctor.getIncome());
    }

    @Test
    void setIncome_withMaxDouble_returnsMaxDouble() {
        doctor.setIncome(Double.MAX_VALUE);
        assertEquals(Double.MAX_VALUE, doctor.getIncome());
    }

    // ─── Multiple field assignments ─────────────────────────────────────────────

    @Test
    void setAllFields_andGetAllFields_returnsCorrectValues() {
        doctor.setDocId(203);
        doctor.setDocName("Dr. Bob");
        doctor.setSpecialization("Orthopedics");
        doctor.setIncome(120000.00);

        assertAll(
            () -> assertEquals(203, doctor.getDocId()),
            () -> assertEquals("Dr. Bob", doctor.getDocName()),
            () -> assertEquals("Orthopedics", doctor.getSpecialization()),
            () -> assertEquals(120000.00, doctor.getIncome())
        );
    }

    @Test
    void setDocId_multipleTimesOverwrites_returnsLastValue() {
        doctor.setDocId(1);
        doctor.setDocId(2);
        doctor.setDocId(3);
        assertEquals(3, doctor.getDocId());
    }

    @Test
    void setDocName_multipleTimesOverwrites_returnsLastValue() {
        doctor.setDocName("First");
        doctor.setDocName("Second");
        assertEquals("Second", doctor.getDocName());
    }

    @Test
    void initialState_allFieldsAreNull() {
        assertAll(
            () -> assertNull(doctor.getDocId()),
            () -> assertNull(doctor.getDocName()),
            () -> assertNull(doctor.getSpecialization()),
            () -> assertNull(doctor.getIncome())
        );
    }
}
