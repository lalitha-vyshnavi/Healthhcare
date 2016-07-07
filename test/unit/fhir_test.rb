require_relative '../test_helper'

class FhirTest < Minitest::Test
	def setup
		@patient = Synthea::Person.new
		@patient[:name_first] = "foo123"
		@patient[:name_last] = "bar456"
		@patient[:gender] = 'F'
		@patient[:address] = {
      'line' => ["4655 Emmerich Springs"],
      'city' => "Bedford",
      'state' => "MA",
      'postalCode' => "01730"
    }
    @patient[:race] = :white
		@patient[:ethnicity] = :italian
		@patient[:is_alive] = true
    @patient[:coordinates_address] = GeoRuby::SimpleFeatures::Point.from_x_y(10,15)
    @fhir_record = FHIR::Bundle.new
    @time = Time.now
    @patient.events.create(@time, :birth, :birth)
    @patient_entry = Synthea::Output::FhirRecord.basic_info(@patient, @fhir_record)
    @encounter = {'type' => :age_lt_11, 'time' => @time}
    @encounter_entry = Synthea::Output::FhirRecord.encounter(@encounter, @fhir_record, @patient_entry)
    @patientID = @fhir_record.entry[0].fullUrl
    @encounterID = @fhir_record.entry[1].fullUrl
	end

  def test_convert_to_fhir
    record = @patient.record_synthea
    record.encounter(:age_lt_11, @time)
    record.condition(:prediabetes, @time, :condition, nil)
    record.procedure(:amputation_left_hand, @time, '46028000', :procedure, nil)
    record.immunization(:rv_mono, @time, :immunization, nil)
    record.observation(:ha1c, @time, 5, :observation, nil)
    record.end_condition(:prediabetes, @time + 10.minutes)
    time_adv = @time + 15.minutes
    record.encounter(:age_lt_11, time_adv)
    record.condition(:diabetes, time_adv, :condition, nil)
    record.procedure(:amputation_right_leg, time_adv, '79733001', :procedure, nil)
    record.immunization(:dtap, time_adv, :immunization, nil)
    record.observation(:height, time_adv, 5, :observation, nil)

    #Add an encounter and 1 entry for each 'category'. Repeat. Check that the order inserted is correct
    fhir = Synthea::Output::FhirRecord.convert_to_fhir(@patient)
    assert_equal(11,fhir.entry.length)
    #test_abatement
    disease = fhir.entry.find {|e| e.resource.is_a?(FHIR::Condition)}.resource
    assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time + 10.minutes, 'time'), disease.abatementDateTime)
    order = [FHIR::Encounter, FHIR::Condition, FHIR::Observation, FHIR::Procedure, FHIR::Immunization]
    order = order.concat(order).unshift(FHIR::Patient)
    order.zip(fhir.entry) do |klass, entry|
      assert_equal(klass, entry.resource.class)
    end
  end
  
  def test_record_blood_pressure
    record = @patient.record_synthea
    record.encounter(:age_lt_11, @time)
    record.observation(:systolic_blood_pressure, @time, 120, :observation, nil)
    record.observation(:diastolic_blood_pressure, @time, 80, :observation, nil)
    record.observation(:blood_pressure, @time, 2, :multi_observation, nil)
    record.observation(:weight, @time, 50, :observation, nil)
    fhir = Synthea::Output::FhirRecord.convert_to_fhir(@patient)
    assert_equal(2,fhir.entry.select {|e| e.resource.is_a?(FHIR::Observation)}.length)
  end

	def test_basic_info
		entry = @fhir_record.entry
		person = entry[0].resource
		name = person.name[0]
		assert_equal(name.given[0], "foo123")
		assert_equal(name.family[0], "bar456")
		assert_equal("official",name.use)
  	assert_equal('female',person.gender)
  	assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time),person.birthDate)
  	assert_match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/,entry[0].fullUrl)
  	race = person.extension[0].valueCodeableConcept.coding[0]
  	assert_equal('Italian',race.display)
  	assert_equal('2114-7',race.code)
  	ethnicity = person.extension[1].valueCodeableConcept.coding[0]
  	assert_equal('Nonhispanic',ethnicity.display)
  	assert_equal('2186-5',ethnicity.code)
  	address = person.address[0]
  	assert_equal('4655 Emmerich Springs', address.line[0])
  	assert_equal('Bedford', address.city)
  	assert_equal('MA', address.state)
  	assert_equal('01730', address.postalCode)
    coordinates = person.extension[2]
    assert_equal('http://standardhealthrecord.org/fhir/extensions/wkt-geospatialpoint', coordinates.url)
    assert_equal('POINT (15, 10)', coordinates.valueString)
  	#test race/ethnicity logic
  	@patient[:race] = :hispanic
  	@patient[:ethnicity] = :mexican
  	Synthea::Output::FhirRecord.basic_info(@patient, @fhir_record)
  	race = @fhir_record.entry[2].resource.extension[0].valueCodeableConcept.coding[0]
  	assert_equal('Other',race.display)
  	assert_equal('2131-1',race.code)
  	ethnicity = @fhir_record.entry[2].resource.extension[1].valueCodeableConcept.coding[0]
  	assert_equal('Mexican',ethnicity.display)
  	assert_equal('2148-5',ethnicity.code)
	end

	def test_condition
		condition = {'type' => :end_stage_renal_disease, 'time' => @time}
		Synthea::Output::FhirRecord.condition(condition, @fhir_record, @patient_entry, @encounter_entry)
		disease_entry = @fhir_record.entry.reverse.find {|e| e.resource.is_a?(FHIR::Condition)}
  	disease = disease_entry.resource
  	assert_equal("Patient/#{@patientID}",disease.patient.reference)
  	assert_equal("46177005", disease.code.coding[0].code)
  	assert_equal('End stage renal disease (disorder)', disease.code.coding[0].display)
  	assert_equal("http://snomed.info/sct", disease.code.coding[0].system)
  	assert_equal('confirmed',disease.verificationStatus)
  	assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time,'time'),disease.onsetDateTime)
  	assert_equal("Encounter/#{@encounterID}",disease.encounter.reference)
	end

	def test_encounter
		encounter = @fhir_record.entry[1].resource
		assert_match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/,@encounterID)
		assert_equal('finished', encounter.status)
		assert_equal('outpatient', encounter.local_class)
		assert_equal('170258001', encounter.type[0].coding[0].code)
		assert_equal('http://snomed.info/sct', encounter.type[0].coding[0].system)
    assert_equal("Patient/#{@patientID}",encounter.patient.reference)
    startTime = Synthea::Output::FhirRecord.convertFhirDateTime(@time,'time')
    endTime = Synthea::Output::FhirRecord.convertFhirDateTime(@time+15.minutes, 'time')
    period = FHIR::Period.new({'start'=>startTime, 'end' => endTime})
    assert_equal(period.start,encounter.period.start)
    assert_equal(period.end, encounter.period.end)
	end

	def test_allergy
		condition = {'type' => :food_allergy_peanuts, 'time' => @time}
		Synthea::Output::FhirRecord.allergy(condition, @fhir_record, @patient_entry, @encounter_entry)
		allergy_entry = @fhir_record.entry.reverse.find {|e| e.resource.is_a?(FHIR::AllergyIntolerance)}
  	allergy = allergy_entry.resource
  	assert_equal("Patient/#{@patientID}",allergy.patient.reference)
  	assert_equal('91935009', allergy.substance.coding[0].code)
  	assert_equal('peanuts', allergy.substance.coding[0].display)
  	assert(allergy.criticality == 'low' || allergy.criticality == 'high')
  	assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time, 'time'), allergy.recordedDate)
		assert_equal('food', allergy.category)
	end

	def test_observation
		observation = {'type' => :height, 'time' => @time, 'value' => "60"}
		Synthea::Output::FhirRecord.observation(observation, @fhir_record, @patient_entry, @encounter_entry)
		obs_entry = @fhir_record.entry.reverse.find {|e| e.resource.is_a?(FHIR::Observation)}
		obs = obs_entry.resource
		assert_match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/,obs_entry.fullUrl)
		assert_equal('final', obs.status)
		assert_equal('http://loinc.org', obs.code.coding[0].system)
		assert_equal('8302-2', obs.code.coding[0].code)
		assert_equal('Body Height', obs.code.coding[0].display)
		assert_equal("Patient/#{@patientID}", obs.subject.reference)
		assert_equal("Encounter/#{@encounterID}", obs.encounter.reference)
		assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time, 'time'), obs.effectiveDateTime)
		assert_equal(60, obs.valueQuantity.value)
		assert_equal("cm", obs.valueQuantity.unit)
	end

  def test_multi_observation
    observation = {'type' => :systolic_blood_pressure, 'time' => @time, 'value' => 120}
    Synthea::Output::FhirRecord.observation(observation, @fhir_record, @patient_entry, @encounter_entry)
    observation = {'type' => :diastolic_blood_pressure, 'time' => @time, 'value' => 80}
    Synthea::Output::FhirRecord.observation(observation, @fhir_record, @patient_entry, @encounter_entry)
    multiobservation = {'type' => :blood_pressure, 'time' =>  @time, 'value' => 2}
    Synthea::Output::FhirRecord.multi_observation(multiobservation, @fhir_record, @patient_entry, @encounter_entry)
    multiobs_entry = @fhir_record.entry.reverse.find {|e| e.resource.is_a?(FHIR::Observation)}
    multiobs = multiobs_entry.resource
    assert_equal(3, @fhir_record.entry.length)
    assert_equal('final',multiobs.status)
    assert_equal('http://loinc.org', multiobs.code.coding[0].system)
    assert_equal('55284-4', multiobs.code.coding[0].code)
    assert_equal('Blood Pressure', multiobs.code.coding[0].display)
    assert_equal("Patient/#{@patientID}", multiobs.subject.reference)
    assert_equal("Encounter/#{@encounterID}", multiobs.encounter.reference)
    assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time, 'time'), multiobs.effectiveDateTime)
    systolic = multiobs.component[0]
    assert_equal('8480-6', systolic.code.coding[0].code)
    assert_equal('Systolic Blood Pressure', systolic.code.coding[0].display)
    assert_equal('http://loinc.org', systolic.code.coding[0].system)
    assert_equal(120, systolic.valueQuantity.value)
    assert_equal("mmHg", systolic.valueQuantity.unit)
    diastolic = multiobs.component[1]
    assert_equal('8462-4', diastolic.code.coding[0].code)
    assert_equal('Diastolic Blood Pressure', diastolic.code.coding[0].display)
    assert_equal('http://loinc.org', diastolic.code.coding[0].system)
    assert_equal(80, diastolic.valueQuantity.value)
    assert_equal("mmHg", diastolic.valueQuantity.unit)

  end

  def test_diagnostic_report
    observation = {'type' => :hdl, 'time' => @time, 'value' => "120"}
    Synthea::Output::FhirRecord.observation(observation, @fhir_record, @patient_entry, @encounter_entry)
    observation = {'type' => :ldl, 'time' => @time, 'value' => "80"}
    Synthea::Output::FhirRecord.observation(observation, @fhir_record, @patient_entry, @encounter_entry)
    ob_refs = @fhir_record.entry.last(2).map{|obs| [obs.fullUrl, obs.resource.code.coding.first.display]}
    report_hash = { 'type' => :lipid_panel, 'time' => @time, 'numObs' => 2}
    Synthea::Output::FhirRecord.diagnostic_report(report_hash, @fhir_record, @patient_entry, @encounter_entry)
    report_entry = @fhir_record.entry.reverse.find {|e| e.resource.is_a?(FHIR::DiagnosticReport)}
    report  = report_entry.resource
    assert_match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/,report_entry.fullUrl)
    assert_equal('final', report.status)
    assert_equal('http://loinc.org', report.code.coding[0].system)
    assert_equal('57698-3', report.code.coding[0].code)
    assert_equal('Lipid Panel', report.code.coding[0].display)
    assert_equal("Patient/#{@patientID}", report.subject.reference)
    assert_equal("Encounter/#{@encounterID}", report.encounter.reference)
    assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time, 'time'), report.effectiveDateTime)
    assert_equal('Hospital Lab', report.performer.display)
    ob_refs.zip(report.result) do |ob, result|
      assert_equal("Observation/#{ob[0]}", result.reference)
      assert_equal(ob[1], result.display)
    end
  end

  def test_procedure
    condition = {'type' => :nephropathy, 'time' => @time}
    Synthea::Output::FhirRecord.condition(condition, @fhir_record, @patient_entry, @encounter_entry)
    proc_hash = { 'type' => :amputation_left_arm , 'time' => @time, 'reason' => '368581000119106'}
    Synthea::Output::FhirRecord.procedure(proc_hash, @fhir_record, @patient_entry, @encounter_entry)
    proc_entry = @fhir_record.entry.reverse.find {|e| e.resource.is_a?(FHIR::Procedure)}
    procedure  = proc_entry.resource
    assert_equal("Patient/#{@patientID}", procedure.subject.reference)
    assert_equal('completed', procedure.status)
    assert_equal('http://snomed.info/sct', procedure.code.coding[0].system)
    assert_equal('13995008', procedure.code.coding[0].code)
    assert_equal('Amputation of left arm', procedure.code.coding[0].display)
    assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time, 'time'), procedure.performedDateTime)
    assert_equal("Encounter/#{@encounterID}", procedure.encounter.reference)
  end

  def test_imm
    imm_hash = { 'type' => :hepb, 'time' => @time}
    Synthea::Output::FhirRecord.immunization(imm_hash, @fhir_record, @patient_entry, @encounter_entry)
    imm = @fhir_record.entry.reverse.find {|e| e.resource.is_a?(FHIR::Immunization)}.resource
    assert_equal('completed', imm.status)
    assert_equal(Synthea::Output::FhirRecord.convertFhirDateTime(@time, 'time'), imm.date)
    assert_equal('08', imm.vaccineCode.coding[0].code)
    assert_equal('http://hl7.org/fhir/sid/cvx', imm.vaccineCode.coding[0].system)
    assert_equal('Hep B, adolescent or pediatric', imm.vaccineCode.coding[0].display)
    assert_equal("Patient/#{@patientID}", imm.patient.reference)
    assert_equal(false, imm.wasNotGiven)
    assert_equal(false, imm.reported)
    assert_equal("Encounter/#{@encounterID}", imm.encounter.reference)
  end
end