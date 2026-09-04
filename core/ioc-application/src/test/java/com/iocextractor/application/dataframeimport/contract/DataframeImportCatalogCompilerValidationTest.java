package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportCatalogCompilerValidationTest {

    private static final String IP_LIST_CONTRACT = "ip-list-v1";

    private final DataframeImportCatalogCompiler compiler = new DataframeImportCatalogCompiler();

    @Test
    void rejectsAbsentCatalogAndEveryIncompleteEnvironmentShape() {
        assertThat(compiler.compile(null, environment()).violations())
                .extracting(ImportContractViolation::message)
                .containsExactly("catalog must be configured");

        List<DataframeImportCatalogEnvironment> incompleteEnvironments = Arrays.asList(
                null,
                new DataframeImportCatalogEnvironment(null, Set.of("lower"), Set.of("upstream")),
                new DataframeImportCatalogEnvironment(Map.of(), null, Set.of("upstream")),
                new DataframeImportCatalogEnvironment(Map.of(), Set.of("lower"), null));

        assertThat(incompleteEnvironments)
                .allSatisfy(incomplete -> assertThat(compiler.compile(validDraft(), incomplete).violations())
                        .extracting(ImportContractViolation::message)
                        .containsExactly("catalog reference environment is incomplete"));
    }

    @Test
    void collectsAuthorityShapeIdentityAllowlistAndCeilingViolations() {
        var invalidArtifacts = Arrays.asList("", "ip_list", "ip_list", "missing");
        var authorities = Arrays.asList(
                (DataframeImportCatalogDraft.AuthorityProfile) null,
                new DataframeImportCatalogDraft.AuthorityProfile("", null, null, false, false),
                new DataframeImportCatalogDraft.AuthorityProfile(
                        "broken", invalidArtifacts, null, false, false),
                authority("duplicate", ImportMergePolicy.AUTHORITATIVE, true, true),
                authority("duplicate", ImportMergePolicy.AUTHORITATIVE, true, true));

        DataframeImportCatalogCompilation compilation = compiler.compile(
                disabledDraft(List.of(), authorities, List.of()), environment());

        assertThat(compilation.violations()).extracting(ImportContractViolation::message)
                .contains(
                        "authority profile must be an object",
                        "authority profile ID must not be blank",
                        "authority artifact allowlist must not be empty",
                        "artifact must not be blank",
                        "artifact must be unique",
                        "authority artifact must reference a configured canonical schema",
                        "maximum merge policy is required",
                        "authority profile ID must be unique");

        assertThat(compiler.compile(
                disabledDraft(List.of(), null, List.of()), environment()).violations())
                .extracting(ImportContractViolation::message)
                .containsExactly("authority profile list must not be null");
    }

    @Test
    void collectsMissingContractShapePolicyCharsetDialectAndArtifactViolations() {
        DataframeImportCatalogDraft.Contract missing = new DataframeImportCatalogDraft.Contract(
                "", 0, null, null, null, null, null, null, null, false,
                null, null, null, null);
        DataframeImportCatalogDraft.Contract invalidDialect = copyContract(
                validContract("invalid-dialect"),
                "bad[",
                new DataframeImportCatalogDraft.Dialect(
                        ";", ";", null, false, Arrays.asList("", "NULL", "NULL")),
                new DataframeImportCatalogDraft.Recognition(List.of(), null, null, null));
        DataframeImportCatalogDraft.Contract unsupportedCharset = copyContract(
                validContract("unsupported-charset"),
                "x-unsupported-charset",
                validDialect(),
                validRecognition());
        var contracts = Arrays.asList(
                (DataframeImportCatalogDraft.Contract) null,
                missing,
                invalidDialect,
                unsupportedCharset,
                validContract("duplicate"),
                validContract("duplicate"));

        DataframeImportCatalogCompilation compilation = compiler.compile(
                disabledDraft(List.of(), List.of(), contracts), environment());

        assertThat(compilation.violations()).extracting(ImportContractViolation::message)
                .contains(
                        "contract must be an object",
                        "contract ID must not be blank",
                        "contract version must be positive",
                        "declared charset is required",
                        "CSV dialect is required",
                        "recognition signature is required",
                        "processing mode is required",
                        "routing policy is required",
                        "row failure policy is required",
                        "duplicate policy is required",
                        "formula policy is required",
                        "default merge policy is required",
                        "at least one artifact mapping is required",
                        "declared charset name is invalid",
                        "declared charset is not supported by this runtime",
                        "quote must differ from delimiter",
                        "record separator policy is required",
                        "V1 requires an explicit header",
                        "null literal must not be blank",
                        "null literal must be unique",
                        "at least one required column is needed for recognition",
                        "column list must not be null",
                        "header alias map must not be null",
                        "contract ID must be unique");

        assertThat(compiler.compile(
                disabledDraft(List.of(), List.of(), null), environment()).violations())
                .extracting(ImportContractViolation::message)
                .containsExactly("contract list must not be null");
    }

    @Test
    void collectsRecognitionSetAndAliasAmbiguitiesWithoutStoppingAtTheFirstHeader() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(null, "ip");
        aliases.put("blank-target", " ");
        aliases.put("unknown-target", "missing");
        aliases.put("first-ip", "ip");
        aliases.put("second-ip", "ip");
        DataframeImportCatalogDraft.Recognition recognition = new DataframeImportCatalogDraft.Recognition(
                Arrays.asList("ip", "", "ip"),
                List.of("score", "ip"),
                List.of("ignored", "score"),
                aliases);
        DataframeImportCatalogDraft.Contract contract = withRecognition(
                validContract(IP_LIST_CONTRACT), recognition);

        DataframeImportCatalogCompilation compilation = compiler.compile(
                disabledDraft(List.of(), List.of(), List.of(contract)), environment());

        assertThat(compilation.violations()).extracting(ImportContractViolation::message)
                .contains(
                        "column name must not be blank",
                        "canonical column must occur in exactly one recognition set",
                        "header aliases must have non-blank source and target",
                        "header alias target must be a declared canonical column",
                        "multiple aliases must not collapse onto one canonical header");
    }

    @Test
    void collectsArtifactRoleSchemaIdentityColumnAndTransformViolations() {
        DataframeImportCatalogDraft.Artifact malformedRelated = new DataframeImportCatalogDraft.Artifact(
                "", ImportArtifactRole.RELATED, "", Arrays.asList("", "unknown", "unknown"),
                ImportMergePolicy.AUTHORITATIVE, Arrays.asList((DataframeImportCatalogDraft.Column) null));
        DataframeImportCatalogDraft.Artifact malformedPrimary = new DataframeImportCatalogDraft.Artifact(
                "ip_list", ImportArtifactRole.PRIMARY, "wrong-row-key",
                Arrays.asList("", "ip-v1", "ip-v1", "missing-match"),
                ImportMergePolicy.AUTHORITATIVE,
                Arrays.asList(
                        (DataframeImportCatalogDraft.Column) null,
                        new DataframeImportCatalogDraft.Column("", "", null, null),
                        new DataframeImportCatalogDraft.Column(
                                "ip", "missing-source", Arrays.asList(null, "missing", "lower:ROOT"), null),
                        new DataframeImportCatalogDraft.Column("ip", "ip", List.of("lower"), null),
                        new DataframeImportCatalogDraft.Column("missing-target", "score", List.of(), null)));
        DataframeImportCatalogDraft.Artifact duplicatePrimary = validArtifact(
                "ip_list", ImportArtifactRole.PRIMARY);
        DataframeImportCatalogDraft.Artifact opaqueRelated = new DataframeImportCatalogDraft.Artifact(
                "opaque", ImportArtifactRole.RELATED, "opaque-row", List.of("opaque-match"), null,
                List.of(new DataframeImportCatalogDraft.Column(
                        "opaque-column", "ip", List.of("lower"), null)));
        var artifacts = Arrays.asList(
                (DataframeImportCatalogDraft.Artifact) null,
                malformedRelated,
                malformedPrimary,
                duplicatePrimary,
                opaqueRelated);
        DataframeImportCatalogDraft.Contract contract = withArtifacts(
                validContract(IP_LIST_CONTRACT), artifacts);

        DataframeImportCatalogCompilation compilation = compiler.compile(
                disabledDraft(List.of(), List.of(), List.of(contract)), environmentWithOpaqueArtifact());

        assertThat(compilation.violations()).extracting(ImportContractViolation::message)
                .contains(
                        "artifact mapping must be an object",
                        "artifact mapping name must be non-blank and unique",
                        "target-only routing must not declare related artifacts",
                        "artifact must reference a configured canonical schema",
                        "record key must reference the artifact's active identity definition",
                        "match-key reference must not be blank",
                        "match-key reference must be unique",
                        "match key must reference an artifact identity definition",
                        "column mapping must be an object",
                        "target column must be non-blank and unique",
                        "target must reference a public artifact column",
                        "source must reference a required or optional canonical header",
                        "transform list must not be null",
                        "transform must reference a registered transform name",
                        "exactly one primary artifact is allowed");
    }

    @Test
    void validatesRequestedSlotAgainstRecognitionAndExportCapabilities() {
        DataframeImportCatalogDraft.Contract validSlotContract = withRecognition(
                withRequestedSlot(
                        validContract(IP_LIST_CONTRACT),
                        new DataframeImportCatalogDraft.RequestedSlot(
                                "slot", "reputation-lists", ImportExistingSlotPolicy.PRESERVE_EXISTING)),
                new DataframeImportCatalogDraft.Recognition(
                        List.of("ip", "score", "slot"), List.of(), List.of(), Map.of()));

        assertThat(compiler.compile(
                disabledDraft(List.of(), List.of(), List.of(validSlotContract)), environment()).valid())
                .isTrue();

        DataframeImportCatalogDraft.Contract malformedSlot = withRequestedSlot(
                validContract("malformed-slot"),
                new DataframeImportCatalogDraft.RequestedSlot("missing", "", null));
        assertThat(compiler.compile(
                disabledDraft(List.of(), List.of(), List.of(malformedSlot)), environment()).violations())
                .extracting(ImportContractViolation::message)
                .contains(
                        "requested slot source must be a mapped recognition column",
                        "requested slot profile is required",
                        "existing-record slot policy is required",
                        "profile must include the primary artifact in stable-slot export");

        DataframeImportCatalogEnvironment withoutSlotCapability = new DataframeImportCatalogEnvironment(
                Map.of("ip_list", new DataframeImportCatalogEnvironment.ArtifactSchema(
                        Set.of("ip", "score"), "ip-row-v1", Set.of("ip-v1"), null, false)),
                Set.of("lower", "upper"), Set.of("upstream"));
        assertThat(compiler.compile(
                disabledDraft(List.of(), List.of(), List.of(validSlotContract)), withoutSlotCapability).violations())
                .extracting(ImportContractViolation::message)
                .contains(
                        "primary artifact has no external export slot",
                        "profile must include the primary artifact in stable-slot export");

        DataframeImportCatalogDraft.Contract withoutPrimary = withRequestedSlot(
                withArtifacts(validContract("without-primary"), List.of()),
                new DataframeImportCatalogDraft.RequestedSlot(
                        "ip", "reputation-lists", ImportExistingSlotPolicy.REJECT_MISMATCH));
        assertThat(compiler.compile(
                disabledDraft(List.of(), List.of(), List.of(withoutPrimary)), environment()).violations())
                .extracting(ImportContractViolation::message)
                .contains("at least one artifact mapping is required");

        DataframeImportCatalogDraft.Artifact unknownPrimary = validArtifact(
                "unknown", ImportArtifactRole.PRIMARY);
        DataframeImportCatalogDraft.Contract withoutSchema = withRequestedSlot(
                withArtifacts(validContract("without-schema"), List.of(unknownPrimary)),
                new DataframeImportCatalogDraft.RequestedSlot(
                        "ip", "reputation-lists", ImportExistingSlotPolicy.PRESERVE_EXISTING));
        assertThat(compiler.compile(
                disabledDraft(List.of(), List.of(), List.of(withoutSchema)), environment()).violations())
                .extracting(ImportContractViolation::message)
                .contains("artifact must reference a configured canonical schema");
    }

    @Test
    void collectsSourceOwnershipReferenceAndEndpointViolations() {
        var sources = Arrays.asList(
                (DataframeImportCatalogDraft.Source) null,
                new DataframeImportCatalogDraft.Source("", null, "", null, null, ""),
                new DataframeImportCatalogDraft.Source(
                        "duplicate", ImportSourceTransport.LOCAL, "./var/import", "upstream",
                        Arrays.asList("", "missing", IP_LIST_CONTRACT, IP_LIST_CONTRACT), "standard"),
                new DataframeImportCatalogDraft.Source(
                        "duplicate", ImportSourceTransport.SMB, "/incoming", "missing-endpoint",
                        List.of(IP_LIST_CONTRACT), "standard"));
        DataframeImportCatalogDraft draft = new DataframeImportCatalogDraft(
                false, sources, List.of(validAuthority()), List.of(validContract(IP_LIST_CONTRACT)));

        DataframeImportCatalogCompilation compilation = compiler.compile(draft, environment());

        assertThat(compilation.violations()).extracting(ImportContractViolation::message)
                .contains(
                        "source must be an object",
                        "source ID must not be blank",
                        "source transport is required",
                        "source location is required",
                        "source contract allowlist must not be empty",
                        "source must reference a configured authority profile",
                        "local source must not configure a remote endpoint",
                        "contract reference must not be blank",
                        "contract reference must be unique",
                        "source must reference a configured contract",
                        "source ID must be unique",
                        "SMB source must reference a configured sync endpoint");

        assertThat(compiler.compile(
                new DataframeImportCatalogDraft(false, null, List.of(), List.of()), environment()).violations())
                .extracting(ImportContractViolation::message)
                .containsExactly("source list must not be null");
    }

    @Test
    void enforcesSourceAuthorityAcrossRoutingFormulaArtifactsAndMergeOverrides() {
        DataframeImportCatalogDraft.Artifact elevatedArtifact = new DataframeImportCatalogDraft.Artifact(
                "ip_list", ImportArtifactRole.PRIMARY, "ip-row-v1", List.of("ip-v1"),
                ImportMergePolicy.AUTHORITATIVE,
                List.of(new DataframeImportCatalogDraft.Column(
                        "ip", "ip", List.of("lower"), ImportMergePolicy.REPLACE_NON_NULL)));
        DataframeImportCatalogDraft.Contract elevated = new DataframeImportCatalogDraft.Contract(
                IP_LIST_CONTRACT, 1, "UTF-8", validDialect(), validRecognition(),
                ImportProcessingMode.AS_IS, ImportRoutingPolicy.RELATED_ARTIFACTS,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.MACHINE_ONLY_PRESERVE, ImportMergePolicy.AUTHORITATIVE,
                List.of(elevatedArtifact), null);
        DataframeImportCatalogDraft.AuthorityProfile restricted =
                new DataframeImportCatalogDraft.AuthorityProfile(
                        "standard", List.of("hashes"), ImportMergePolicy.FILL_MISSING, false, false);
        DataframeImportCatalogDraft draft = new DataframeImportCatalogDraft(
                true, List.of(validSource()), List.of(restricted), List.of(elevated));

        DataframeImportCatalogCompilation compilation = compiler.compile(draft, environment());

        assertThat(compilation.violations()).extracting(ImportContractViolation::message)
                .contains(
                        "source authority does not permit related-artifact routing",
                        "source authority does not permit machine-only formula preservation",
                        "contract default merge policy exceeds source authority",
                        "contract artifact is outside the source authority allowlist",
                        "artifact or column merge override exceeds source authority");
    }

    @Test
    void acceptsExplicitlyAuthorizedRelatedRoutingAndMachineFormulaPreservation() {
        DataframeImportCatalogDraft.Artifact related = new DataframeImportCatalogDraft.Artifact(
                "hashes", ImportArtifactRole.RELATED, "hash-row-v1", List.of("hash-v1"), null,
                List.of(new DataframeImportCatalogDraft.Column(
                        "hash", "hash", List.of("upper"), null)));
        DataframeImportCatalogDraft.Contract contract = new DataframeImportCatalogDraft.Contract(
                IP_LIST_CONTRACT, 1, "UTF-8", validDialect(),
                new DataframeImportCatalogDraft.Recognition(
                        List.of("ip", "score", "hash"), List.of(), List.of(), Map.of()),
                ImportProcessingMode.AS_IS, ImportRoutingPolicy.RELATED_ARTIFACTS,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.MACHINE_ONLY_PRESERVE, ImportMergePolicy.AUTHORITATIVE,
                List.of(validArtifact("ip_list", ImportArtifactRole.PRIMARY), related), null);
        DataframeImportCatalogDraft.AuthorityProfile authority =
                new DataframeImportCatalogDraft.AuthorityProfile(
                        "standard", List.of("ip_list", "hashes"),
                        ImportMergePolicy.AUTHORITATIVE, true, true);
        Map<String, DataframeImportCatalogEnvironment.ArtifactSchema> artifacts = new LinkedHashMap<>(
                environment().artifacts());
        artifacts.put("hashes", new DataframeImportCatalogEnvironment.ArtifactSchema(
                Set.of("hash"), "hash-row-v1", Set.of("hash-v1"), Set.of("reputation-lists"), true));
        DataframeImportCatalogEnvironment relatedEnvironment = new DataframeImportCatalogEnvironment(
                artifacts, environment().transforms(), environment().endpoints());

        DataframeImportCatalogCompilation compilation = compiler.compile(
                new DataframeImportCatalogDraft(
                        true, List.of(validSource()), List.of(authority), List.of(contract)),
                relatedEnvironment);

        assertThat(compilation.valid()).isTrue();
        assertThat(compilation.catalogOrThrow().contracts()).hasSize(1);
    }

    @Test
    void validatesReferencedNullCollectionsAndBlankRemoteIdentityWithoutShortCircuiting() {
        DataframeImportCatalogDraft.Artifact nullCollectionsPrimary =
                new DataframeImportCatalogDraft.Artifact(
                        "ip_list", ImportArtifactRole.PRIMARY, "ip-row-v1",
                        null, ImportMergePolicy.FILL_MISSING, null);
        DataframeImportCatalogDraft.Artifact missingRole =
                new DataframeImportCatalogDraft.Artifact(
                        "ip_list", null, "ip-row-v1", List.of("ip-v1"),
                        null, List.of());
        DataframeImportCatalogDraft.Contract malformed = new DataframeImportCatalogDraft.Contract(
                "referenced-malformed", 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(
                        ";;", "\"", ImportRecordSeparator.CRLF_OR_LF, true, null),
                validRecognition(), ImportProcessingMode.AS_IS, ImportRoutingPolicy.TARGET_ONLY,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.REJECT, ImportMergePolicy.FILL_MISSING,
                Arrays.asList(null, nullCollectionsPrimary, missingRole),
                new DataframeImportCatalogDraft.RequestedSlot(
                        " ", "reputation-lists", ImportExistingSlotPolicy.PRESERVE_EXISTING));
        DataframeImportCatalogDraft.AuthorityProfile authority =
                new DataframeImportCatalogDraft.AuthorityProfile(
                        "null-artifacts", null, ImportMergePolicy.AUTHORITATIVE, false, false);
        List<DataframeImportCatalogDraft.Source> sources = List.of(
                new DataframeImportCatalogDraft.Source(
                        "blank-smb", ImportSourceTransport.SMB, "/incoming", " ",
                        List.of("referenced-malformed"), "null-artifacts"),
                new DataframeImportCatalogDraft.Source(
                        "missing-authority", ImportSourceTransport.LOCAL, "./var/import", null,
                        List.of("referenced-malformed"), "not-configured"));

        DataframeImportCatalogCompilation compilation = compiler.compile(
                disabledDraft(sources, List.of(authority), List.of(malformed)), environment());

        assertThat(compilation.violations()).extracting(ImportContractViolation::message)
                .contains(
                        "SMB source must reference a configured sync endpoint",
                        "source must reference a configured authority profile",
                        "delimiter must contain exactly one Unicode code point",
                        "null literal list must not be null",
                        "artifact mapping must be an object",
                        "artifact role is required",
                        "at least one match-key definition is required",
                        "at least one column mapping is required",
                        "requested slot source must be a mapped recognition column",
                        "contract artifact is outside the source authority allowlist");
    }

    @Test
    void compilationOutcomeCannotMixCatalogAndViolations() {
        DataframeImportCatalog catalog = compiler.compile(validDraft(), environment()).catalogOrThrow();
        ImportContractViolation violation = new ImportContractViolation(
                "contracts[0]", "contract is invalid");

        assertThatThrownBy(() -> new DataframeImportCatalogCompilation(
                java.util.Optional.of(catalog), List.of(violation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly when");
        assertThatThrownBy(() -> new DataframeImportCatalogCompilation(
                java.util.Optional.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly when");

        DataframeImportCatalogCompilation failed = new DataframeImportCatalogCompilation(
                java.util.Optional.empty(), List.of(violation));
        assertThat(failed.valid()).isFalse();
        assertThatThrownBy(failed::catalogOrThrow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contracts[0]: contract is invalid");
    }

    @Test
    void safeContractAndParserFailuresRejectUnstableDiagnosticEvidence() {
        ImportContractViolation violation = new ImportContractViolation(
                "contracts[0]", "contract is invalid");
        assertThat(violation.path()).isEqualTo("contracts[0]");
        assertThatThrownBy(() -> new ImportContractViolation(" ", "contract is invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> new ImportContractViolation("contracts[0]", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatNullPointerException()
                .isThrownBy(() -> new ImportContractViolation(null, "contract is invalid"))
                .withMessage("path");
        assertThatNullPointerException()
                .isThrownBy(() -> new ImportContractViolation("contracts[0]", null))
                .withMessage("message");

        var defaulted = new DelimitedInputReadException(null, "invalid input");
        var defaultedWithCause = new DelimitedInputReadException(
                null, "invalid input", new IllegalStateException("decoder failed"));
        assertThat(defaulted.reason()).isEqualTo(DelimitedInputReadException.Reason.PARSER_FAILURE);
        assertThat(defaultedWithCause.reason()).isEqualTo(DelimitedInputReadException.Reason.PARSER_FAILURE);
        assertThat(DelimitedInputReadException.Reason.fromReportCode(
                DelimitedInputReadException.Reason.HEADER_MISMATCH.reportCode()))
                .contains(DelimitedInputReadException.Reason.HEADER_MISMATCH);
        assertThat(DelimitedInputReadException.Reason.fromReportCode("IMPORT.UNKNOWN"))
                .isEmpty();
    }

    private static DataframeImportCatalogDraft validDraft() {
        return new DataframeImportCatalogDraft(
                true,
                List.of(validSource()),
                List.of(validAuthority()),
                List.of(validContract(IP_LIST_CONTRACT)));
    }

    private static DataframeImportCatalogDraft disabledDraft(
            List<DataframeImportCatalogDraft.Source> sources,
            List<DataframeImportCatalogDraft.AuthorityProfile> authorities,
            List<DataframeImportCatalogDraft.Contract> contracts) {
        return new DataframeImportCatalogDraft(false, sources, authorities, contracts);
    }

    private static DataframeImportCatalogDraft.Source validSource() {
        return new DataframeImportCatalogDraft.Source(
                "local", ImportSourceTransport.LOCAL, "./var/import", null,
                List.of(IP_LIST_CONTRACT), "standard");
    }

    private static DataframeImportCatalogDraft.AuthorityProfile validAuthority() {
        return authority("standard", ImportMergePolicy.AUTHORITATIVE, false, false);
    }

    private static DataframeImportCatalogDraft.AuthorityProfile authority(
            String id,
            ImportMergePolicy ceiling,
            boolean allowRelated,
            boolean allowFormulaPreserve) {
        return new DataframeImportCatalogDraft.AuthorityProfile(
                id, List.of("ip_list"), ceiling, allowRelated, allowFormulaPreserve);
    }

    private static DataframeImportCatalogDraft.Contract validContract(String id) {
        return new DataframeImportCatalogDraft.Contract(
                id, 1, "UTF-8", validDialect(), validRecognition(),
                ImportProcessingMode.AS_IS, ImportRoutingPolicy.TARGET_ONLY,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.REJECT, ImportMergePolicy.AUTHORITATIVE,
                List.of(validArtifact("ip_list", ImportArtifactRole.PRIMARY)), null);
    }

    private static DataframeImportCatalogDraft.Dialect validDialect() {
        return new DataframeImportCatalogDraft.Dialect(
                ";", "\"", ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL"));
    }

    private static DataframeImportCatalogDraft.Recognition validRecognition() {
        return new DataframeImportCatalogDraft.Recognition(
                List.of("ip", "score"), List.of(), List.of(), Map.of());
    }

    private static DataframeImportCatalogDraft.Artifact validArtifact(
            String name,
            ImportArtifactRole role) {
        return new DataframeImportCatalogDraft.Artifact(
                name, role, "ip-row-v1", List.of("ip-v1"), null,
                List.of(
                        new DataframeImportCatalogDraft.Column("ip", "ip", List.of("lower"), null),
                        new DataframeImportCatalogDraft.Column("score", "score", List.of(), null)));
    }

    private static DataframeImportCatalogDraft.Contract copyContract(
            DataframeImportCatalogDraft.Contract original,
            String charset,
            DataframeImportCatalogDraft.Dialect dialect,
            DataframeImportCatalogDraft.Recognition recognition) {
        return new DataframeImportCatalogDraft.Contract(
                original.id(), original.version(), charset, dialect, recognition,
                original.mode(), original.routing(), original.rowFailurePolicy(),
                original.duplicatePolicy(), original.renewUnchanged(), original.formulaPolicy(),
                original.mergeDefault(), original.artifacts(), original.requestedSlot());
    }

    private static DataframeImportCatalogDraft.Contract withRecognition(
            DataframeImportCatalogDraft.Contract original,
            DataframeImportCatalogDraft.Recognition recognition) {
        return copyContract(original, original.charset(), original.dialect(), recognition);
    }

    private static DataframeImportCatalogDraft.Contract withArtifacts(
            DataframeImportCatalogDraft.Contract original,
            List<DataframeImportCatalogDraft.Artifact> artifacts) {
        return new DataframeImportCatalogDraft.Contract(
                original.id(), original.version(), original.charset(), original.dialect(), original.recognition(),
                original.mode(), original.routing(), original.rowFailurePolicy(),
                original.duplicatePolicy(), original.renewUnchanged(), original.formulaPolicy(),
                original.mergeDefault(), artifacts, original.requestedSlot());
    }

    private static DataframeImportCatalogDraft.Contract withRequestedSlot(
            DataframeImportCatalogDraft.Contract original,
            DataframeImportCatalogDraft.RequestedSlot requestedSlot) {
        return new DataframeImportCatalogDraft.Contract(
                original.id(), original.version(), original.charset(), original.dialect(), original.recognition(),
                original.mode(), original.routing(), original.rowFailurePolicy(),
                original.duplicatePolicy(), original.renewUnchanged(), original.formulaPolicy(),
                original.mergeDefault(), original.artifacts(), requestedSlot);
    }

    private static DataframeImportCatalogEnvironment environment() {
        return new DataframeImportCatalogEnvironment(
                Map.of("ip_list", new DataframeImportCatalogEnvironment.ArtifactSchema(
                        Set.of("ip", "score"), "ip-row-v1", Set.of("ip-v1"),
                        Set.of("reputation-lists"), true)),
                Set.of("lower", "upper"),
                Set.of("upstream"));
    }

    private static DataframeImportCatalogEnvironment environmentWithOpaqueArtifact() {
        Map<String, DataframeImportCatalogEnvironment.ArtifactSchema> artifacts = new LinkedHashMap<>(
                environment().artifacts());
        artifacts.put("opaque", new DataframeImportCatalogEnvironment.ArtifactSchema(
                null, "opaque-row", null, null, false));
        return new DataframeImportCatalogEnvironment(
                artifacts, environment().transforms(), environment().endpoints());
    }
}
