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
    void setDocId_storesValue() {
        doctor.setDocId(10);
        assertEquals(10, doctor.getDocId());
    }

    @Test
    void setDocName_storesValue() {
        doctor.setDocName("Alice");
        assertEquals("Alice", doctor.getDocName());
    }

    @Test
    void setSpecialization_storesValue() {
        doctor.setSpecialization("Cardiology");
        assertEquals("Cardiology", doctor.getSpecialization());
    }

    @Test
    void setIncome_storesValue() {
        doctor.setIncome(1234.5);
        assertEquals(1234.5, doctor.getIncome());
    }

    @Test
    void setters_acceptNull() {
        doctor.setDocId(1);
        doctor.setDocId(null);
        doctor.setDocName("x");
        doctor.setDocName(null);
        doctor.setSpecialization("y");
        doctor.setSpecialization(null);
        doctor.setIncome(1.0);
        doctor.setIncome(null);
        assertNull(doctor.getDocId());
        assertNull(doctor.getDocName());
        assertNull(doctor.getSpecialization());
        assertNull(doctor.getIncome());
    }
}
