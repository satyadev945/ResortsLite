package com.demo.resortslite.entity;

// Updated from javax.persistence.* to jakarta.persistence.* for Java 25 / Jakarta EE 10
// JAVA8_TO_25_JAKARTA_EE_MIGRATION: javax.* packages removed in Spring Boot 3.x / Hibernate 6;
// all JPA annotations now live under the jakarta.persistence namespace.
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "JPA_DOCTOR_INFO")
public class Doctor {

    @Column(name = "DOC_ID")
    @Id
    @SequenceGenerator(name = "gen1", sequenceName = "CNO_SEQ", initialValue = 203, allocationSize = 1)
    @GeneratedValue(generator = "gen1", strategy = GenerationType.SEQUENCE)
    private Integer docId;

    @Column(name = "DOC_NAME", length = 25)
    private String docName;

    @Column(name = "SPECIALIZATION", length = 20)
    private String specialization;

    @Column(name = "INCOME")
    private Double income;

    public Integer getDocId() {
        return docId;
    }

    public void setDocId(Integer docId) {
        this.docId = docId;
    }

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public String getSpecialization() {
        return specialization;
    }

    public void setSpecialization(String specialization) {
        this.specialization = specialization;
    }

    public Double getIncome() {
        return income;
    }

    public void setIncome(Double income) {
        this.income = income;
    }
}
