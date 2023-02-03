package org.mitre.synthea.export.rif;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mitre.synthea.TestHelper;
import org.mitre.synthea.engine.Generator;
import org.mitre.synthea.engine.Module;
import org.mitre.synthea.export.Exporter;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.DefaultRandomNumberGenerator;
import org.mitre.synthea.helpers.RandomNumberGenerator;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.world.concepts.Claim;
import org.mitre.synthea.world.concepts.HealthRecord.Code;

public class BB2RIFExporterTest {

  public class MockMapper extends CodeMapper {
    public MockMapper() {
      super("MOCK_MAPPING_FILE_THAT_DOES_NOT_EXIST.JSON");
    }

    @Override
    public boolean canMap(Code codeToMap) {
      return true;
    }

    @Override
    public boolean hasMap() {
      return true;
    }

    /**
     * Get one of the BFD codes for the supplied Synthea code.
     * @param codeToMap the Synthea code to look for
     * @param bfdCodeType the type of BFD code to map to
     * @param rand a source of random numbers used to pick one of the list of BFD codes
     * @param stripDots whether to remove dots in codes (e.g. J39.45 -> J3945)
     * @return the BFD code or null if the code can't be mapped
     */
    @Override
    public String map(Code codeToMap, String bfdCodeType, RandomNumberGenerator rand,
        boolean stripDots) {
      if (bfdCodeType.equalsIgnoreCase("code")) {
        return "XXXX";
      } else {
        return "R";
      }
    }
  }

  /**
   * Temporary folder for any exported files, guaranteed to be deleted at the end of the test.
   */
  @ClassRule
  public static TemporaryFolder tempFolder = new TemporaryFolder();

  private static File exportDir;

  /**
   * Global setup for export tests.
   * @throws Exception if something goes wrong
   */
  @BeforeClass
  public static void setUpExportDir() throws Exception {
    TestHelper.exportOff();
    TestHelper.loadTestProperties();
    Generator.DEFAULT_STATE = Config.get("test_state.default", "Massachusetts");
    Config.set("exporter.bfd.export", "true");
    Config.set("exporter.bfd.require_code_maps", "false");
    Config.set("exporter.years_of_history", "10");
    Config.set("generate.only_alive_patients", "true");
    exportDir = tempFolder.newFolder();
    Config.set("exporter.baseDirectory", exportDir.toString());
    BB2RIFExporter.getInstance().prepareOutputFiles();
  }

  @Test
  public void testBB2Export() throws Exception {
    BB2RIFExporter.getInstance().dmeCodeMapper = new MockMapper();

    URI uri = BB2RIFExporterTest.class.getResource("/module").toURI();
    File file = new File(uri);
    Module.addModules(file);
    int numberOfPeople = 10;
    Exporter.ExporterRuntimeOptions exportOpts = new Exporter.ExporterRuntimeOptions();
    Generator.GeneratorOptions generatorOpts = new Generator.GeneratorOptions();
    generatorOpts.population = numberOfPeople;
    generatorOpts.seed = 505;
    RandomNumberGenerator rand = new DefaultRandomNumberGenerator(generatorOpts.seed);
    generatorOpts.minAge = 70;
    generatorOpts.maxAge = 80;
    generatorOpts.ageSpecified = true;
    generatorOpts.overflow = false;
    Generator generator = new Generator(generatorOpts, exportOpts);
    for (int i = 0; i < numberOfPeople; i++) {
      generator.generatePerson(i, rand.randLong());
    }
    // Adding post completion exports to generate organizations and providers CSV files
    Exporter.runPostCompletionExports(generator, exportOpts);

    // if we get here we at least had no exceptions

    Path expectedExportPath = exportDir.toPath().resolve("bfd");
    File expectedExportFolder = expectedExportPath.toFile();
    assertTrue(expectedExportFolder.exists() && expectedExportFolder.isDirectory());

    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**/beneficiary_*.csv");
    List<File> beneFiles = Files.list(expectedExportPath)
                .filter(Files::isRegularFile)
                .filter((path) -> matcher.matches(path))
                .map(Path::toFile)
                .collect(Collectors.toList());
    assertTrue("Expected at least one beneficiary file", beneFiles.size() > 0);
    for (File beneficiaryFile: beneFiles) {
      assertTrue(beneficiaryFile.exists() && beneficiaryFile.isFile());
      String csvData = new String(Files.readAllBytes(beneficiaryFile.toPath()));

      // the BB2 exporter doesn't use the SimpleCSV class to write the data,
      // so we can use it here for a level of validation
      List<LinkedHashMap<String, String>> rows = SimpleCSV.parse(csvData, '|');
      assertTrue(
              "Expected at least 1 row in the beneficiary file, found " + rows.size(),
              rows.size() >= 1);
      rows.forEach(row -> {
        assertTrue("Expected non-zero length surname",
                row.containsKey("BENE_SRNM_NAME") && row.get("BENE_SRNM_NAME").length() > 0);
      });
    }

    // TODO: more meaningful testing of contents
    File beneficiaryHistoryFile = expectedExportFolder.toPath()
            .resolve("beneficiary_history.csv").toFile();
    assertTrue(beneficiaryHistoryFile.exists() && beneficiaryHistoryFile.isFile());


    // Check that other expected files are present but only if the corresponding code mapping files
    // are present, otherwise the files could be empty and in that case they aren't created.
    BB2RIFExporter bb2Exporter = BB2RIFExporter.getInstance();

    if (bb2Exporter.conditionCodeMapper.hasMap() && bb2Exporter.hcpcsCodeMapper.hasMap()) {
      // TODO: more meaningful testing of contents (e.g. count of claims)
      File inpatientFile = expectedExportFolder.toPath().resolve("inpatient.csv").toFile();
      assertTrue(inpatientFile.exists() && inpatientFile.isFile());
      File outpatientFile = expectedExportFolder.toPath().resolve("outpatient.csv").toFile();
      assertTrue(outpatientFile.exists() && outpatientFile.isFile());
      File carrierFile = expectedExportFolder.toPath().resolve("carrier.csv").toFile();
      assertTrue(carrierFile.exists() && carrierFile.isFile());
      validateClaimTotals(carrierFile);
    }

    if (bb2Exporter.conditionCodeMapper.hasMap() && bb2Exporter.dmeCodeMapper.hasMap()) {
      // TODO: more meaningful testing of contents
      File dmeFile = expectedExportFolder.toPath().resolve("dme.csv").toFile();
      assertTrue(dmeFile.exists() && dmeFile.isFile());
      validateClaimTotals(dmeFile);
    }

    if (bb2Exporter.medicationCodeMapper.hasMap()) {
      File pdeFile = expectedExportFolder.toPath().resolve("pde.csv").toFile();
      assertTrue(pdeFile.exists() && pdeFile.isFile());
      validatePDETotals(pdeFile);
    }
  }

  private static class ClaimTotals {
    private final String claimID;
    private final BigDecimal claimPaymentAmount;
    private final BigDecimal claimAllowedAmount;
    private final BigDecimal claimBenePaymentAmount;
    private final BigDecimal claimBeneDDblAmount;
    private final BigDecimal claimProviderPaymentAmount;
    private BigDecimal linePaymentAmount;
    private BigDecimal linePaymentAmountTotal;
    private BigDecimal lineBenePaymentAmount;
    private BigDecimal lineBenePaymentAmountTotal;
    private BigDecimal lineBeneDDblAmountTotal;
    private BigDecimal lineProviderPaymentAmount;
    private BigDecimal lineProviderPaymentAmountTotal;

    ClaimTotals(LinkedHashMap<String, String> row) {
      claimID = row.get("CLM_ID");
      claimPaymentAmount = new BigDecimal(row.get("CLM_PMT_AMT")).setScale(2);
      claimAllowedAmount = new BigDecimal(row.get("NCH_CARR_CLM_ALOWD_AMT")).setScale(2);
      claimBenePaymentAmount = new BigDecimal(row.get("NCH_CLM_BENE_PMT_AMT")).setScale(2);
      claimBeneDDblAmount = new BigDecimal(row.get("CARR_CLM_CASH_DDCTBL_APLD_AMT")).setScale(2);
      claimProviderPaymentAmount = new BigDecimal(row.get("NCH_CLM_PRVDR_PMT_AMT")).setScale(2);
      linePaymentAmountTotal = Claim.ZERO_CENTS;
      lineBenePaymentAmountTotal = Claim.ZERO_CENTS;
      lineProviderPaymentAmountTotal = Claim.ZERO_CENTS;
      lineBeneDDblAmountTotal = Claim.ZERO_CENTS;
    }

    void addLineItems(LinkedHashMap<String, String> row) {
      linePaymentAmount = new BigDecimal(row.get("LINE_NCH_PMT_AMT")).setScale(2);
      linePaymentAmountTotal = linePaymentAmountTotal.add(linePaymentAmount);
      lineBenePaymentAmount = new BigDecimal(row.get("LINE_BENE_PMT_AMT")).setScale(2);
      lineBenePaymentAmountTotal = lineBenePaymentAmountTotal.add(lineBenePaymentAmount);
      lineProviderPaymentAmount = new BigDecimal(row.get("LINE_PRVDR_PMT_AMT")).setScale(2);
      lineProviderPaymentAmountTotal = lineProviderPaymentAmountTotal
              .add(lineProviderPaymentAmount);
      lineBeneDDblAmountTotal =  lineBeneDDblAmountTotal
              .add(new BigDecimal(row.get("LINE_BENE_PTB_DDCTBL_AMT")).setScale(2));
    }
  }

  private void validateClaimTotals(File file) throws IOException {
    String csvData = new String(Files.readAllBytes(file.toPath()));

    // the BB2 exporter doesn't use the SimpleCSV class to write the data,
    // so we can use it here for a level of validation
    List<LinkedHashMap<String, String>> rows = SimpleCSV.parse(csvData, '|');
    assertTrue(
            "Expected at least 1 row in " + file.getName() + ", found " + rows.size(),
            rows.size() >= 1);
    Map<String, ClaimTotals> claims = new HashMap<>();
    rows.forEach(row -> {
      assertTrue("Expected non-zero length claim ID",
          row.containsKey("CLM_ID") && row.get("CLM_ID").length() > 0);
      String claimID = row.get("CLM_ID");
      if (!claims.containsKey(claimID)) {
        ClaimTotals claim = new ClaimTotals(row);
        claims.put(claimID, claim);
      }
      ClaimTotals claim = claims.get(claimID);
      claim.addLineItems(row);
      assertTrue(claim.linePaymentAmount.equals(
              claim.lineBenePaymentAmount.add(claim.lineProviderPaymentAmount)));
    });

    claims.values().forEach(claim -> {
      assertTrue(claim.claimPaymentAmount.equals(
              claim.claimBenePaymentAmount.add(claim.claimProviderPaymentAmount)));
      assertTrue(claim.linePaymentAmountTotal.equals(claim.claimAllowedAmount));
      assertTrue(claim.lineBenePaymentAmountTotal.equals(claim.claimBenePaymentAmount));
      assertTrue(claim.lineProviderPaymentAmountTotal.equals(claim.claimProviderPaymentAmount));
      assertTrue(claim.lineBeneDDblAmountTotal.equals(claim.claimBeneDDblAmount));
    });
  }

  private static class PDETotals {
    private final String pdeID;
    private final BigDecimal lineTotalRxAmount;
    private final BigDecimal linePatientAmount;
    private final BigDecimal lineOtherPocketAmount;
    private final BigDecimal lineSubsidizedAmount;
    private final BigDecimal lineOtherInsuranceAmount;
    private final BigDecimal linePartDCoveredAmount;
    private final BigDecimal linePartDNotCoveredAmount;

    PDETotals(LinkedHashMap<String, String> row) {
      pdeID = row.get("PDE_ID");
      lineTotalRxAmount = new BigDecimal(row.get("TOT_RX_CST_AMT")).setScale(2);
      linePatientAmount = new BigDecimal(row.get("PTNT_PAY_AMT")).setScale(2);
      lineOtherPocketAmount = new BigDecimal(row.get("OTHR_TROOP_AMT")).setScale(2);
      lineSubsidizedAmount = new BigDecimal(row.get("LICS_AMT")).setScale(2);
      lineOtherInsuranceAmount = new BigDecimal(row.get("PLRO_AMT")).setScale(2);
      linePartDCoveredAmount = new BigDecimal(row.get("CVRD_D_PLAN_PD_AMT")).setScale(2);
      linePartDNotCoveredAmount = new BigDecimal(row.get("NCVRD_PLAN_PD_AMT")).setScale(2);
    }

    boolean isValid() {
      BigDecimal sum = Claim.ZERO_CENTS;
      sum = sum.add(linePatientAmount).add(lineOtherPocketAmount).add(lineSubsidizedAmount);
      sum = sum.add(lineOtherInsuranceAmount).add(linePartDCoveredAmount);
      sum = sum.add(linePartDNotCoveredAmount);
      return (sum.compareTo(lineTotalRxAmount) == 0);
    }
  }

  private void validatePDETotals(File file) throws IOException {
    String csvData = new String(Files.readAllBytes(file.toPath()));

    // the BB2 exporter doesn't use the SimpleCSV class to write the data,
    // so we can use it here for a level of validation
    List<LinkedHashMap<String, String>> rows = SimpleCSV.parse(csvData, '|');
    assertTrue(
            "Expected at least 1 row in the claim file, found " + rows.size(),
            rows.size() >= 1);
    Set<String> pdeIds = new HashSet<>();
    rows.forEach(row -> {
      assertTrue("Expected non-zero length PDE ID",
          row.containsKey("PDE_ID") && row.get("PDE_ID").length() > 0);
      String pdeId = row.get("PDE_ID");
      assertFalse("PDE IDs should be unique.", pdeIds.contains(pdeId));
      pdeIds.add(pdeId);
      PDETotals event = new PDETotals(row);
      assertTrue("PDE dollar amounts do not add up correctly.", event.isValid());
    });
  }
}
