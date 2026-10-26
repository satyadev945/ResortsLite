package com.demo.resortslite.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link Doctor} entity.
 */
class DoctorTest {

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        doctor = new Doctor();
    }

    // -----------------------------------------------------------------------
    // Default constructor
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("defaultConstructor_createsNonNullInstance")
    void defaultConstructor_createsNonNullInstance() {
        // Assert
        assertNotNull(doctor);
    }

    @Test
    @DisplayName("defaultConstructor_allFieldsAreNull")
    void defaultConstructor_allFieldsAreNull() {
        // Assert
        assertNull(doctor.getDocId());
        assertNull(doctor.getDocName());
        assertNull(doctor.getSpecialization());
        assertNull(doctor.getIncome());
    }

    // -----------------------------------------------------------------------
    // setDocId / getDocId
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("setDocId_validId_getDocIdReturnsCorrectValue")
    void setDocId_validId_getDocIdReturnsCorrectValue() {
        // Arrange
        Integer expectedId = 203;

        // Act
        doctor.setDocId(expectedId);

        // Assert
        assertEquals(expectedId, doctor.getDocId());
    }

    @Test
    @DisplayName("setDocId_zero_getDocIdReturnsZero")
    void setDocId_zero_getDocIdReturnsZero() {
        // Act
        doctor.setDocId(0);

        // Assert
        assertEquals(0, doctor.getDocId());
    }

    @Test
    @DisplayName("setDocId_negativeValue_getDocIdReturnsNegative")
    void setDocId_negativeValue_getDocIdReturnsNegative() {
        // Act
        doctor.setDocId(-1);

        // Assert
        assertEquals(-1, doctor.getDocId());
    }

    @Test
    @DisplayName("setDocId_null_getDocIdReturnsNull")
    void setDocId_null_getDocIdReturnsNull() {
        // Arrange
        doctor.setDocId(100);

        // Act
        doctor.setDocId(null);

        // Assert
        assertNull(doctor.getDocId());
    }

    @Test
    @DisplayName("setDocId_largeValue_getDocIdReturnsLargeValue")
    void setDocId_largeValue_getDocIdReturnsLargeValue() {
        // Act
        doctor.setDocId(Integer.MAX_VALUE);

        // Assert
        assertEquals(Integer.MAX_VALUE, doctor.getDocId());
    }

    // -----------------------------------------------------------------------
    // setDocName / getDocName
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("setDocName_validName_getDocNameReturnsCorrectValue")
    void setDocName_validName_getDocNameReturnsCorrectValue() {
        // Act
        doctor.setDocName("Dr. Smith");

        // Assert
        assertEquals("Dr. Smith", doctor.getDocName());
    }

    @Test
    @DisplayName("setDocName_emptyString_getDocNameReturnsEmpty")
    void setDocName_emptyString_getDocNameReturnsEmpty() {
        // Act
        doctor.setDocName("");

        // Assert
        assertEquals("", doctor.getDocName());
    }

    @Test
    @DisplayName("setDocName_null_getDocNameReturnsNull")
    void setDocName_null_getDocNameReturnsNull() {
        // Arrange
        doctor.setDocName("Dr. Jones");

        // Act
        doctor.setDocName(null);

        // Assert
        assertNull(doctor.getDocName());
    }

    @Test
    @DisplayName("setDocName_maxLength25_getDocNameReturnsValue")
    void setDocName_maxLength25_getDocNameReturnsValue() {
        // Arrange – exactly 25 characters
        String name = "Dr. Alexander Hamiltonxyz";

        // Act
        doctor.setDocName(name);

        // Assert
        assertEquals(name, doctor.getDocName());
    }

    @Test
    @DisplayName("setDocName_nameWithSpecialChars_getDocNameReturnsValue")
    void setDocName_nameWithSpecialChars_getDocNameReturnsValue() {
        // Act
        doctor.setDocName("Dr. O'Brien-Smith");

        // Assert
        assertEquals("Dr. O'Brien-Smith", doctor.getDocName());
    }

    // -----------------------------------------------------------------------
    // setSpecialization / getSpecialization
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("setSpecialization_validValue_getSpecializationReturnsCorrectValue")
    void setSpecialization_validValue_getSpecializationReturnsCorrectValue() {
        // Act
        doctor.setSpecialization("Cardiology");

        // Assert
        assertEquals("Cardiology", doctor.getSpecialization());
    }

    @Test
    @DisplayName("setSpecialization_emptyString_getSpecializationReturnsEmpty")
    void setSpecialization_emptyString_getSpecializationReturnsEmpty() {
        // Act
        doctor.setSpecialization("");

        // Assert
        assertEquals("", doctor.getSpecialization());
    }

    @Test
    @DisplayName("setSpecialization_null_getSpecializationReturnsNull")
    void setSpecialization_null_getSpecializationReturnsNull() {
        // Arrange
        doctor.setSpecialization("Neurology");

        // Act
        doctor.setSpecialization(null);

        // Assert
        assertNull(doctor.getSpecialization());
    }

    @Test
    @DisplayName("setSpecialization_maxLength20_getSpecializationReturnsValue")
    void setSpecialization_maxLength20_getSpecializationReturnsValue() {
        // Arrange – exactly 20 characters
        String spec = "Gastroenterologyyy!!";

        // Act
        doctor.setSpecialization(spec);

        // Assert
        assertEquals(spec, doctor.getSpecialization());
    }

    @Test
    @DisplayName("setSpecialization_differentValues_getSpecializationReturnsLatest")
    void setSpecialization_differentValues_getSpecializationReturnsLatest() {
        // Act
        doctor.setSpecialization("Cardiology");
        doctor.setSpecialization("Neurology");

        // Assert
        assertEquals("Neurology", doctor.getSpecialization());
    }

    // -----------------------------------------------------------------------
    // setIncome / getIncome
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("setIncome_validDouble_getIncomeReturnsCorrectValue")
    void setIncome_validDouble_getIncomeReturnsCorrectValue() {
        // Act
        doctor.setIncome(75000.50);

        // Assert
        assertEquals(75000.50, doctor.getIncome(), 0.001);
    }

    @Test
    @DisplayName("setIncome_zero_getIncomeReturnsZero")
    void setIncome_zero_getIncomeReturnsZero() {
        // Act
        doctor.setIncome(0.0);

        // Assert
        assertEquals(0.0, doctor.getIncome(), 0.001);
    }

    @Test
    @DisplayName("setIncome_negativeValue_getIncomeReturnsNegative")
    void setIncome_negativeValue_getIncomeReturnsNegative() {
        // Act
        doctor.setIncome(-1000.0);

        // Assert
        assertEquals(-1000.0, doctor.getIncome(), 0.001);
    }

    @Test
    @DisplayName("setIncome_null_getIncomeReturnsNull")
    void setIncome_null_getIncomeReturnsNull() {
        // Arrange
        doctor.setIncome(50000.0);

        // Act
        doctor.setIncome(null);

        // Assert
        assertNull(doctor.getIncome());
    }

    @Test
    @DisplayName("setIncome_maxDouble_getIncomeReturnsMaxDouble")
    void setIncome_maxDouble_getIncomeReturnsMaxDouble() {
        // Act
        doctor.setIncome(Double.MAX_VALUE);

        // Assert
        assertEquals(Double.MAX_VALUE, doctor.getIncome(), 0.001);
    }

    // -----------------------------------------------------------------------
    // Full entity population
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("doctor_fullyPopulated_allGettersReturnCorrectValues")
    void doctor_fullyPopulated_allGettersReturnCorrectValues() {
        // Arrange
        doctor.setDocId(203);
        doctor.setDocName("Dr. Alice");
        doctor.setSpecialization("Cardiology");
        doctor.setIncome(120000.00);

        // Assert
        assertEquals(203, doctor.getDocId());
        assertEquals("Dr. Alice", doctor.getDocName());
        assertEquals("Cardiology", doctor.getSpecialization());
        assertEquals(120000.00, doctor.getIncome(), 0.001);
    }

    @Test
    @DisplayName("doctor_settersChained_lastValueWins")
    void doctor_settersChained_lastValueWins() {
        // Act
        doctor.setDocId(1);
        doctor.setDocId(2);
        doctor.setDocId(3);

        // Assert
        assertEquals(3, doctor.getDocId());
    }

    @Test
    @DisplayName("twoDoctors_independentInstances_noSharedState")
    void twoDoctors_independentInstances_noSharedState() {
        // Arrange
        Doctor doctor1 = new Doctor();
        Doctor doctor2 = new Doctor();

        // Act
        doctor1.setDocId(1);
        doctor1.setDocName("Dr. One");
        doctor2.setDocId(2);
        doctor2.setDocName("Dr. Two");

        // Assert
        assertNotEquals(doctor1.getDocId(), doctor2.getDocId());
        assertNotEquals(doctor1.getDocName(), doctor2.getDocName());
    }
}
