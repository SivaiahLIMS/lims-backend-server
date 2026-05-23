package com.sivayahealth.lims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sivayahealth.lims.entity.*;
import com.sivayahealth.lims.exception.LimsException;
import com.sivayahealth.lims.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMasterRepository documentRepository;
    private final DocumentHistoryRepository documentHistoryRepository;
    private final DocumentParsedJsonRepository parsedJsonRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final WorksheetExecutionRepository worksheetRepository;
    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final DocxParserService docxParserService;
    private final ObjectMapper objectMapper;

    /**
     * Upload a DOCX file for a document master.
     * Parses the file using Apache POI and stores the extracted JSON schema.
     */
    @Transactional
    public DocumentVersion uploadDocxVersion(Long documentId, Long tenantId, Long branchId,
                                              MultipartFile file, Long uploadedById) {
        DocumentMaster doc = documentRepository.findById(documentId)
                .orElseThrow(() -> LimsException.notFound("Document not found"));
        AppUser uploader = userRepository.findById(uploadedById).orElse(null);

        validateDocxFile(file);

        // Determine next version number
        int nextVersion = documentVersionRepository.findByDocument_Id(documentId)
                .stream()
                .mapToInt(DocumentVersion::getVersionNo)
                .max().orElse(0) + 1;

        // Parse the DOCX
        DocxParserService.ParsedDocxResult parsed;
        try (InputStream in = file.getInputStream()) {
            parsed = docxParserService.parse(in);
        } catch (IOException e) {
            throw new LimsException("Failed to read uploaded file: " + e.getMessage());
        }

        // Build schema JSON
        ObjectNode schemaJson = objectMapper.createObjectNode();
        schemaJson.set("fields",   objectMapper.valueToTree(parsed.fields()));
        schemaJson.set("formulas", objectMapper.valueToTree(parsed.formulas()));
        schemaJson.set("sections", parsed.sections());
        schemaJson.put("documentId",  documentId);
        schemaJson.put("versionNo",   nextVersion);
        schemaJson.put("parsedAt",    LocalDateTime.now().toString());
        schemaJson.put("fieldCount",  parsed.fields().size());
        schemaJson.put("formulaCount", parsed.formulas().size());

        // Save version record
        DocumentVersion version = DocumentVersion.builder()
                .tenantId(tenantId)
                .branchId(branchId)
                .document(doc)
                .versionNo(nextVersion)
                .lifecycleState("DRAFT")
                .filePath(file.getOriginalFilename())
                .uploadedAt(LocalDateTime.now())
                .uploadedBy(uploader)
                .build();
        DocumentVersion savedVersion = documentVersionRepository.save(version);

        // Persist parsed schema
        DocumentParsedJson parsedRecord = DocumentParsedJson.builder()
                .document(doc)
                .version(nextVersion)
                .parsedJson(schemaJson.toString())
                .build();
        parsedJsonRepository.save(parsedRecord);

        auditService.log(tenantId, uploadedById, "DocumentVersion", savedVersion.getId(),
                "UPLOAD_DOCX", null, doc.getName() + " v" + nextVersion + " (" + parsed.fields().size() + " fields)");

        return savedVersion;
    }

    /** Lifecycle: DRAFT → UNDER_REVIEW */
    @Transactional
    public DocumentVersion submitForReview(Long documentId, int versionNo, Long userId) {
        DocumentVersion version = getVersion(documentId, versionNo);
        requireState(version, "DRAFT");
        version.setLifecycleState("UNDER_REVIEW");
        return documentVersionRepository.save(version);
    }

    /** Lifecycle: UNDER_REVIEW → APPROVED */
    @Transactional
    public DocumentVersion approveVersion(Long documentId, int versionNo, Long reviewerId, String comment) {
        DocumentVersion version = getVersion(documentId, versionNo);
        requireState(version, "UNDER_REVIEW");
        AppUser reviewer = userRepository.findById(reviewerId).orElse(null);
        version.setLifecycleState("APPROVED");
        version.setReviewedBy(reviewer);
        version.setReviewedAt(LocalDateTime.now());
        version.setReviewComment(comment);
        return documentVersionRepository.save(version);
    }

    /** Lifecycle: APPROVED → PUBLISHED */
    @Transactional
    public DocumentVersion publishVersion(Long documentId, int versionNo, Long userId) {
        DocumentVersion version = getVersion(documentId, versionNo);
        requireState(version, "APPROVED");
        AppUser approver = userRepository.findById(userId).orElse(null);
        version.setLifecycleState("PUBLISHED");
        version.setApprovedBy(approver);
        version.setApprovedAt(LocalDateTime.now());
        version.setPublishedAt(LocalDateTime.now());
        return documentVersionRepository.save(version);
    }

    /** Lifecycle: PUBLISHED → RETIRED */
    @Transactional
    public DocumentVersion retireVersion(Long documentId, int versionNo) {
        DocumentVersion version = getVersion(documentId, versionNo);
        version.setLifecycleState("RETIRED");
        return documentVersionRepository.save(version);
    }

    @Transactional
    public DocumentMaster createDocument(Long tenantId, String name, String type, Long uploadedById) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> LimsException.notFound("Tenant not found"));
        AppUser uploader = userRepository.findById(uploadedById).orElse(null);

        DocumentMaster doc = DocumentMaster.builder()
                .tenant(tenant).name(name).type(type)
                .version(1).status("ACTIVE").uploadedBy(uploader)
                .build();
        DocumentMaster saved = documentRepository.save(doc);
        auditService.log(tenantId, uploadedById, "DocumentMaster", saved.getId(), "CREATE", null, name);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<DocumentMaster> getDocuments(Long tenantId) {
        return documentRepository.findByTenantIdAndStatus(tenantId, "ACTIVE");
    }

    @Transactional(readOnly = true)
    public List<DocumentVersion> getVersions(Long documentId) {
        return documentVersionRepository.findByDocument_Id(documentId);
    }

    @Transactional(readOnly = true)
    public DocumentParsedJson getParsedJson(Long documentId, int versionNo) {
        return parsedJsonRepository.findByDocument_IdAndVersion(documentId, versionNo)
                .orElseThrow(() -> LimsException.notFound("Parsed JSON not found for version " + versionNo));
    }

    @Transactional
    public WorksheetExecution submitWorksheet(Long documentId, Long sampleId,
                                               String filledJson, Long executedById) {
        DocumentMaster doc = documentRepository.findById(documentId)
                .orElseThrow(() -> LimsException.notFound("Document not found"));
        AppUser executor = userRepository.findById(executedById)
                .orElseThrow(() -> LimsException.notFound("User not found"));

        WorksheetExecution execution = WorksheetExecution.builder()
                .document(doc).version(doc.getVersion())
                .sampleId(sampleId).executedBy(executor)
                .status("SUBMITTED").filledJson(filledJson)
                .build();
        WorksheetExecution saved = worksheetRepository.save(execution);
        auditService.log(doc.getTenant().getId(), executedById, "WorksheetExecution",
                saved.getId(), "SUBMIT", null, "SUBMITTED");
        return saved;
    }

    @Transactional
    public WorksheetExecution approveWorksheet(Long executionId, Long approvedById) {
        WorksheetExecution execution = worksheetRepository.findById(executionId)
                .orElseThrow(() -> LimsException.notFound("Worksheet execution not found"));
        execution.setStatus("APPROVED");
        WorksheetExecution saved = worksheetRepository.save(execution);
        auditService.log(execution.getDocument().getTenant().getId(), approvedById,
                "WorksheetExecution", executionId, "APPROVE", "SUBMITTED", "APPROVED");
        return saved;
    }

    @Transactional
    public WorksheetExecution rejectWorksheet(Long executionId, Long rejectedById) {
        WorksheetExecution execution = worksheetRepository.findById(executionId)
                .orElseThrow(() -> LimsException.notFound("Worksheet execution not found"));
        execution.setStatus("REJECTED");
        return worksheetRepository.save(execution);
    }

    // ---- helpers ----

    private DocumentVersion getVersion(Long documentId, int versionNo) {
        return documentVersionRepository.findByDocument_IdAndVersionNo(documentId, versionNo)
                .orElseThrow(() -> LimsException.notFound("Version " + versionNo + " not found for document " + documentId));
    }

    private void requireState(DocumentVersion v, String expected) {
        if (!expected.equals(v.getLifecycleState())) {
            throw new LimsException("Cannot transition from '" + v.getLifecycleState()
                    + "'. Expected state: " + expected);
        }
    }

    private void validateDocxFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new LimsException("Uploaded file is empty");
        }
        String name = file.getOriginalFilename();
        if (name == null || (!name.endsWith(".docx") && !name.endsWith(".DOCX"))) {
            throw new LimsException("Only .docx files are supported");
        }
    }
}
