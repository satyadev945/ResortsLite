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
    void newDoctor_hasNullFields() {
        assertNull(doctor.getDocId());
        assertNull(doctor.getDocName());
        assertNull(doctor.getSpecialization());
        assertNull(doctor.getIncome());
    }

    @Test
    void setDocId_updatesValue() {
        doctor.setDocId(5);
        assertEquals(5, doctor.getDocId());
    }

    @Test
    void setDocName_updatesValue() {
        doctor.setDocName("Alice");
        assertEquals("Alice", doctor.getDocName());
    }

    @Test
    void setSpecialization_updatesValue() {
        doctor.setSpecialization("Cardiology");
        assertEquals("Cardiology", doctor.getSpecialization());
    }

    @Test
    void setIncome_updatesValue() {
        doctor.setIncome(1234.5);
        assertEquals(1234.5, doctor.getIncome());
    }

    @Test
    void setters_acceptNull() {
        doctor.setDocId(1);
        doctor.setDocId(null);
        doctor.setDocName(null);
        doctor.setSpecialization(null);
        doctor.setIncome(null);
        assertNull(doctor.getDocId());
        assertNull(doctor.getIncome());
    }

    @Test
    void entityAnnotations_present() {
        assertTrue(Doctor.class.isAnnotationPresent(jakarta.persistence.Entity.class));
        assertEquals("JPA_DOCTOR_INFO", Doctor.class.getAnnotation(jakarta.persistence.Table.class).name());
    }
}
