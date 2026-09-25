package com.demo.resortslite.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DoctorTest {

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        doctor = new Doctor();
    }

    // --- Constructor test ---

    @Test
    void doctor_defaultConstructor_createsInstance() {
        // Act
        Doctor newDoctor = new Doctor();

        // Assert
        assertNotNull(newDoctor);
        assertNull(newDoctor.getDocId());
        assertNull(newDoctor.getDocName());
        assertNull(newDoctor.getSpecialization());
        assertNull(newDoctor.getIncome());
    }

    // --- getDocId / setDocId tests ---

    @Test
    void setDocId_withValidId_getsDocIdReturnsValue() {
        // Arrange
        Integer docId = 203;

        // Act
        doctor.setDocId(docId);

        // Assert
        assertEquals(docId, doctor.getDocId());
    }

    @Test
    void setDocId_withNullId_getsDocIdReturnsNull() {
        // Arrange
        doctor.setDocId(100);

        // Act
        doctor.setDocId(null);

        // Assert
        assertNull(doctor.getDocId());
    }

    @Test
    void setDocId_withLargeId_getsDocIdReturnsValue() {
        // Arrange
        Integer docId = 999999;

        // Act
        doctor.setDocId(docId);

        // Assert
        assertEquals(docId, doctor.getDocId());
    }

    // --- getDocName / setDocName tests ---

    @Test
    void setDocName_withValidName_getsDocNameReturnsValue() {
        // Arrange
        String docName = "Dr. Smith";

        // Act
        doctor.setDocName(docName);

        // Assert
        assertEquals(docName, doctor.getDocName());
    }

    @Test
    void setDocName_withNullName_getsDocNameReturnsNull() {
        // Arrange
        doctor.setDocName("Dr. Jones");

        // Act
        doctor.setDocName(null);

        // Assert
        assertNull(doctor.getDocName());
    }

    @Test
    void setDocName_withEmptyName_getsDocNameReturnsEmpty() {
        // Arrange
        String docName = "";

        // Act
        doctor.setDocName(docName);

        // Assert
        assertEquals("", doctor.getDocName());
    }

    // --- getSpecialization / setSpecialization tests ---

    @Test
    void setSpecialization_withValidSpecialization_getsSpecializationReturnsValue() {
        // Arrange
        String specialization = "Cardiology";

        // Act
        doctor.setSpecialization(specialization);

        // Assert
        assertEquals(specialization, doctor.getSpecialization());
    }

    @Test
    void setSpecialization_withNullSpecialization_getsSpecializationReturnsNull() {
        // Arrange
        doctor.setSpecialization("Neurology");

        // Act
        doctor.setSpecialization(null);

        // Assert
        assertNull(doctor.getSpecialization());
    }

    @Test
    void setSpecialization_withEmptySpecialization_getsSpecializationReturnsEmpty() {
        // Arrange
        String specialization = "";

        // Act
        doctor.setSpecialization(specialization);

        // Assert
        assertEquals("", doctor.getSpecialization());
    }

    // --- getIncome / setIncome tests ---

    @Test
    void setIncome_withValidIncome_getsIncomeReturnsValue() {
        // Arrange
        Double income = 150000.50;

        // Act
        doctor.setIncome(income);

        // Assert
        assertEquals(income, doctor.getIncome());
    }

    @Test
    void setIncome_withNullIncome_getsIncomeReturnsNull() {
        // Arrange
        doctor.setIncome(50000.0);

        // Act
        doctor.setIncome(null);

        // Assert
        assertNull(doctor.getIncome());
    }

    @Test
    void setIncome_withZeroIncome_getsIncomeReturnsZero() {
        // Arrange
        Double income = 0.0;

        // Act
        doctor.setIncome(income);

        // Assert
        assertEquals(0.0, doctor.getIncome());
    }

    @Test
    void setIncome_withNegativeIncome_getsIncomeReturnsNegative() {
        // Arrange
        Double income = -5000.0;

        // Act
        doctor.setIncome(income);

        // Assert
        assertEquals(-5000.0, doctor.getIncome());
    }

    // --- Full object test ---

    @Test
    void doctor_setAllFields_allGettersReturnCorrectValues() {
        // Arrange
        Integer docId = 203;
        String docName = "Dr. House";
        String specialization = "Diagnostic Medicine";
        Double income = 250000.75;

        // Act
        doctor.setDocId(docId);
        doctor.setDocName(docName);
        doctor.setSpecialization(specialization);
        doctor.setIncome(income);

        // Assert
        assertEquals(docId, doctor.getDocId());
        assertEquals(docName, doctor.getDocName());
        assertEquals(specialization, doctor.getSpecialization());
        assertEquals(income, doctor.getIncome());
    }
}
