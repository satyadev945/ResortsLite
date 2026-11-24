package com.demo.resortslite.entity;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void setDocId_getDocId_roundTrip() {
        doctor.setDocId(203);
        assertEquals(203, doctor.getDocId());
    }

    @Test
    void setDocName_getDocName_roundTrip() {
        doctor.setDocName("Alice");
        assertEquals("Alice", doctor.getDocName());
    }

    @Test
    void setSpecialization_getSpecialization_roundTrip() {
        doctor.setSpecialization("Cardiology");
        assertEquals("Cardiology", doctor.getSpecialization());
    }

    @Test
    void setIncome_getIncome_roundTrip() {
        doctor.setIncome(12345.67);
        assertEquals(12345.67, doctor.getIncome());
    }

    @Test
    void setters_acceptNull() {
        doctor.setDocId(1);
        doctor.setDocId(null);
        doctor.setDocName("x");
        doctor.setDocName(null);
        doctor.setSpecialization("x");
        doctor.setSpecialization(null);
        doctor.setIncome(1.0);
        doctor.setIncome(null);
        assertNull(doctor.getDocId());
        assertNull(doctor.getDocName());
        assertNull(doctor.getSpecialization());
        assertNull(doctor.getIncome());
    }

    @Test
    void class_hasEntityAndTableAnnotations() {
        assertTrue(Doctor.class.isAnnotationPresent(Entity.class));
        assertEquals("jpa_doctor_info", Doctor.class.getAnnotation(Table.class).name());
    }
}
