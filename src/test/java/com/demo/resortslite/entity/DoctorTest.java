package com.demo.resortslite.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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
    void newDoctor_fieldsAreNull() {
        assertNull(doctor.getDocId());
        assertNull(doctor.getDocName());
        assertNull(doctor.getSpecialization());
        assertNull(doctor.getIncome());
    }

    @Test
    void setDocId_getDocId() {
        doctor.setDocId(5);
        assertEquals(5, doctor.getDocId());
    }

    @Test
    void setDocName_getDocName() {
        doctor.setDocName("Who");
        assertEquals("Who", doctor.getDocName());
    }

    @Test
    void setSpecialization_getSpecialization() {
        doctor.setSpecialization("Cardio");
        assertEquals("Cardio", doctor.getSpecialization());
    }

    @Test
    void setIncome_getIncome() {
        doctor.setIncome(1234.5);
        assertEquals(1234.5, doctor.getIncome());
    }

    @Test
    void setters_acceptNull() {
        doctor.setDocName("x");
        doctor.setDocName(null);
        assertNull(doctor.getDocName());
    }

    @Test
    void class_hasJpaAnnotations() {
        assertNotNull(Doctor.class.getAnnotation(Entity.class));
        assertEquals("jpa_doctor_info", Doctor.class.getAnnotation(Table.class).name());
    }
}
