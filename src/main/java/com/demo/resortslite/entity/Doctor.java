package com.demo.resortslite.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name="JPA_DOCTOR_INFO")
public class Doctor {

	@Column(name="DOC_ID")
	@Id
	// PostgreSQL uses an IDENTITY/SERIAL column; Oracle uses a sequence (CNO_SEQ)
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer docId;

	@Column(name="DOC_NAME",length=25,columnDefinition = "varchar(255)")
	private String docName;

	@Column(name="SPECIALIZATION",length=20,columnDefinition = "varchar(255)")
	private String specialization;

	@Column(name="INCOME")
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
