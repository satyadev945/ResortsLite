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

    @Test
    void testDefaultConstructor_createsNonNullInstance() {
        // Act & Assert
        assertNotNull(doctor);
    }

    @Test
    void testSetAndGetDocId_withValidId_returnsCorrectValue() {
        // Arrange
        Integer docId = 101;

        // Act
        doctor.setDocId(docId);

        // Assert
        assertEquals(docId, doctor.getDocId());
    }

    @Test
    void testSetAndGetDocId_withNullId_returnsNull() {
        // Act
        doctor.setDocId(null);

        // Assert
        assertNull(doctor.getDocId());
    }

    @Test
    void testSetAndGetDocId_withZeroId_returnsZero() {
        // Arrange
        Integer docId = 0;

        // Act
        doctor.setDocId(docId);

        // Assert
        assertEquals(0, doctor.getDocId());
    }

    @Test
    void testSetAndGetDocId_withNegativeId_returnsNegativeValue() {
        // Arrange
        Integer docId = -1;

        // Act
        doctor.setDocId(docId);

        // Assert
        assertEquals(-1, doctor.getDocId());
    }

    @Test
    void testSetAndGetDocName_withValidName_returnsCorrectValue() {
        // Arrange
        String docName = "Dr. John Smith";

        // Act
        doctor.setDocName(docName);

        // Assert
        assertEquals(docName, doctor.getDocName());
    }

    @Test
    void testSetAndGetDocName_withEmptyName_returnsEmptyString() {
        // Arrange
        String docName = "";

        // Act
        doctor.setDocName(docName);

        // Assert
        assertEquals("", doctor.getDocName());
    }

    @Test
    void testSetAndGetDocName_withNullName_returnsNull() {
        // Act
        doctor.setDocName(null);

        // Assert
        assertNull(doctor.getDocName());
    }

    @Test
    void testSetAndGetDocName_withLongName_returnsCorrectValue() {
        // Arrange
        String docName = "Dr. Alexander Benjamin Christopher";

        // Act
        doctor.setDocName(docName);

        // Assert
        assertEquals(docName, doctor.getDocName());
    }

    @Test
    void testSetAndGetDocName_withSpecialCharacters_returnsCorrectValue() {
        // Arrange
        String docName = "Dr. O'Brien-Smith";

        // Act
        doctor.setDocName(docName);

        // Assert
        assertEquals(docName, doctor.getDocName());
    }

    @Test
    void testSetAndGetSpecialization_withValidSpecialization_returnsCorrectValue() {
        // Arrange
        String specialization = "Cardiology";

        // Act
        doctor.setSpecialization(specialization);

        // Assert
        assertEquals(specialization, doctor.getSpecialization());
    }

    @Test
    void testSetAndGetSpecialization_withEmptySpecialization_returnsEmptyString() {
        // Arrange
        String specialization = "";

        // Act
        doctor.setSpecialization(specialization);

        // Assert
        assertEquals("", doctor.getSpecialization());
    }

    @Test
    void testSetAndGetSpecialization_withNullSpecialization_returnsNull() {
        // Act
        doctor.setSpecialization(null);

        // Assert
        assertNull(doctor.getSpecialization());
    }

    @Test
    void testSetAndGetSpecialization_withDifferentSpecializations_returnsCorrectValues() {
        // Test multiple specializations
        String[] specializations = {"Neurology", "Orthopedics", "Pediatrics", "Dermatology"};

        for (String spec : specializations) {
            doctor.setSpecialization(spec);
            assertEquals(spec, doctor.getSpecialization());
        }
    }

    @Test
    void testSetAndGetIncome_withValidIncome_returnsCorrectValue() {
        // Arrange
        Double income = 150000.50;

        // Act
        doctor.setIncome(income);

        // Assert
        assertEquals(income, doctor.getIncome());
    }

    @Test
    void testSetAndGetIncome_withZeroIncome_returnsZero() {
        // Arrange
        Double income = 0.0;

        // Act
        doctor.setIncome(income);

        // Assert
        assertEquals(0.0, doctor.getIncome());
    }

    @Test
    void testSetAndGetIncome_withNullIncome_returnsNull() {
        // Act
        doctor.setIncome(null);

        // Assert
        assertNull(doctor.getIncome());
    }

    @Test
    void testSetAndGetIncome_withNegativeIncome_returnsNegativeValue() {
        // Arrange
        Double income = -1000.0;

        // Act
        doctor.setIncome(income);

        // Assert
        assertEquals(-1000.0, doctor.getIncome());
    }

    @Test
    void testSetAndGetIncome_withLargeIncome_returnsCorrectValue() {
        // Arrange
        Double income = 999999999.99;

        // Act
        doctor.setIncome(income);

        // Assert
        assertEquals(income, doctor.getIncome());
    }

    @Test
    void testSetAndGetIncome_withSmallDecimalIncome_returnsCorrectValue() {
        // Arrange
        Double income = 0.01;

        // Act
        doctor.setIncome(income);

        // Assert
        assertEquals(0.01, doctor.getIncome());
    }

    @Test
    void testAllFieldsTogether_withValidData_returnsCorrectValues() {
        // Arrange
        Integer docId = 203;
        String docName = "Dr. Jane Doe";
        String specialization = "Cardiology";
        Double income = 180000.75;

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

    @Test
    void testAllFieldsTogether_withNullValues_returnsNullValues() {
        // Act
        doctor.setDocId(null);
        doctor.setDocName(null);
        doctor.setSpecialization(null);
        doctor.setIncome(null);

        // Assert
        assertNull(doctor.getDocId());
        assertNull(doctor.getDocName());
        assertNull(doctor.getSpecialization());
        assertNull(doctor.getIncome());
    }

    @Test
    void testMultipleSetOperations_overwritesPreviousValues() {
        // Arrange & Act
        doctor.setDocId(100);
        doctor.setDocId(200);

        doctor.setDocName("First Name");
        doctor.setDocName("Second Name");

        doctor.setSpecialization("First Spec");
        doctor.setSpecialization("Second Spec");

        doctor.setIncome(100000.0);
        doctor.setIncome(200000.0);

        // Assert
        assertEquals(200, doctor.getDocId());
        assertEquals("Second Name", doctor.getDocName());
        assertEquals("Second Spec", doctor.getSpecialization());
        assertEquals(200000.0, doctor.getIncome());
    }

    @Test
    void testEntityAnnotationPresent() {
        // Verify that the @Entity annotation is present
        assertTrue(Doctor.class.isAnnotationPresent(jakarta.persistence.Entity.class));
    }

    @Test
    void testTableAnnotationPresent() {
        // Verify that the @Table annotation is present
        assertTrue(Doctor.class.isAnnotationPresent(jakarta.persistence.Table.class));
    }

    @Test
    void testTableName_isCorrect() {
        // Verify that the table name is correct
        jakarta.persistence.Table tableAnnotation = Doctor.class.getAnnotation(jakarta.persistence.Table.class);
        assertEquals("jpa_doctor_info", tableAnnotation.name());
    }

    @Test
    void testDoctorClass_isPublic() {
        // Verify that the Doctor class is public
        assertTrue(java.lang.reflect.Modifier.isPublic(Doctor.class.getModifiers()));
    }

    @Test
    void testDoctorClass_hasDefaultConstructor() {
        // Verify that the Doctor class can be instantiated with default constructor
        assertDoesNotThrow(() -> new Doctor());
    }
}
