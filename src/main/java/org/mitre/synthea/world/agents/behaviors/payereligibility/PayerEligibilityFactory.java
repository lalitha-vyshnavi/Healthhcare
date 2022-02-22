package org.mitre.synthea.world.agents.behaviors.payereligibility;

import org.mitre.synthea.modules.HealthInsuranceModule;

/**
 * Returns the requested Payer elgibility algorithm. This prevents redundant
 * recreations of the same objects over and over.
 */
public class PayerEligibilityFactory {

  private static IPayerEligibility medicareEligibilty = new StandardMedicareEligibility();
  private static IPayerEligibility medicaidEligibility = new StandardMedicaidEligibility();
  private static IPayerEligibility genericEligibility = new GenericPayerEligibilty();

  /**
   * Returns the correct elgibility algorithm based on the payer's name. It uses
   * names of either Medicare or Medicaid.
   * @param payerName The name of the payer.
   * @return  The requested payer eligibilty algorithm.
   */
  public static IPayerEligibility getPayerEligibilityAlgorithm(String payerName) {
    if (payerName.equalsIgnoreCase(HealthInsuranceModule.MEDICAID)) {
      return PayerEligibilityFactory.medicaidEligibility;
    } else if (payerName.equalsIgnoreCase(HealthInsuranceModule.MEDICARE)) {
      return PayerEligibilityFactory.medicareEligibilty;
    }
    return PayerEligibilityFactory.genericEligibility;
  }
}