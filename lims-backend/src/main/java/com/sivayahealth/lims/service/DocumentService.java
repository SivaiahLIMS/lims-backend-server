package com.sivayahealth.lims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sivayahealth.lims.config.MetricsConfig;
import com.sivayahealth.lims.entity.*;
import com.sivayahealth.lims.exception.LimsException;
import com.sivayahealth.lims.repository.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Logger log = LogManager.getLogger(DocumentService.class);

    private final DocumentMasterRepository documentRepository;
    private final DocumentHistoryRepository documentHistoryRepository;
    private final DocumentParsedJsonRepository parsedJsonRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final WorksheetExecutionRepository worksheetRepository;
    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final DocxParserService docxParserService;
    private final GcsStorageService gcsStorageService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Counter documentUploadCounter;
    private final Counter documentPublishedCounter;
    private final Counter documentRetiredCounter;
    private final Timer gcsUploadTimer;

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;

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

        // Upload DOCX to Google Cloud Storage and record metrics
        String storagePath = null;
        String fileUrl = null;
        Long fileSizeBytes = null;
        try {
            byte[] fileBytes = file.getBytes();
            String safeFilename = file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
            String gcsPath = tenantId + "/" + documentId + "/v" + nextVersion + "/" + safeFilename;
            String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

            GcsStorageService.UploadResult result = gcsUploadTimer.recordCallable(
                    () -> gcsStorageService.upload(fileBytes, gcsPath, mimeType, 365 * 10)
            );
            storagePath   = result.storagePath();
            fileUrl       = result.signedUrl();
            fileSizeBytes = result.fileSizeBytes();

            log.info("GCS upload complete: path={} size={}", storagePath, fileSizeBytes);
            documentUploadCounter.increment();

            // Notify Supabase audit trail asynchronously (non-blocking, non-fatal)
            notifySupabaseUploadAudit(tenantId, branchId, documentId, nextVersion,
                    uploadedById, uploader, file.getOriginalFilename(),
                    storagePath, fileUrl, fileBytes.length, mimeType);

        } catch (Exception e) {
            log.warn("GCS upload failed for document {}: {}", documentId, e.getMessage());
            auditService.log(tenantId, uploadedById, "DocumentVersion", null,
                    "UPLOAD_STORAGE_FAILED", null, e.getMessage());
        }

        // Save version record
        DocumentVersion version = DocumentVersion.builder()
                .tenantId(tenantId)
                .branchId(branchId)
                .document(doc)
                .versionNo(nextVersion)
                .lifecycleState("DRAFT")
                .originalFilename(file.getOriginalFilename())
                .storagePath(storagePath)
                .fileUrl(fileUrl)
                .fileSizeBytes(fileSizeBytes)
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
        DocumentVersion saved = documentVersionRepository.save(version);
        auditService.log(version.getTenantId(), userId, "DocumentVersion", saved.getId(),
                "SUBMIT_FOR_REVIEW", "DRAFT", "UNDER_REVIEW");
        insertFileAudit(version, "SUBMITTED_FOR_REVIEW", userId);
        return saved;
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
        DocumentVersion saved = documentVersionRepository.save(version);
        auditService.log(version.getTenantId(), reviewerId, "DocumentVersion", saved.getId(),
                "APPROVE", "UNDER_REVIEW", "APPROVED");
        insertFileAudit(version, "APPROVED", reviewerId);
        return saved;
    }

    /** Lifecycle: APPROVED → PUBLISHED */
    @Transactional
    public DocumentVersion publishVersion(Long documentId, int versionNo, Long userId) {
        DocumentVersion version = getVersion(documentId, versionNo);
        requireState(version, "APPROVED");
        AppUser publisher = userRepository.findById(userId).orElse(null);
        version.setLifecycleState("PUBLISHED");
        version.setApprovedBy(publisher);
        version.setApprovedAt(LocalDateTime.now());
        version.setPublishedAt(LocalDateTime.now());
        version.setPublishedBy(publisher);
        DocumentVersion saved = documentVersionRepository.save(version);
        auditService.log(version.getTenantId(), userId, "DocumentVersion", saved.getId(),
                "PUBLISH", "APPROVED", "PUBLISHED");
        insertFileAudit(version, "PUBLISHED", userId);
        documentPublishedCounter.increment();
        log.info("Document version published: documentId={} version={} by userId={}",
                documentId, versionNo, userId);
        return saved;
    }

    /** Lifecycle: PUBLISHED → RETIRED */
    @Transactional
    public DocumentVersion retireVersion(Long documentId, int versionNo, Long userId) {
        DocumentVersion version = getVersion(documentId, versionNo);
        requireState(version, "PUBLISHED");
        AppUser retirer = userRepository.findById(userId).orElse(null);
        version.setLifecycleState("RETIRED");
        version.setRetiredAt(LocalDateTime.now());
        version.setRetiredBy(retirer);
        DocumentVersion saved = documentVersionRepository.save(version);
        auditService.log(version.getTenantId(), userId, "DocumentVersion", saved.getId(),
                "RETIRE", "PUBLISHED", "RETIRED");
        insertFileAudit(version, "RETIRED", userId);
        documentRetiredCounter.increment();
        log.info("Document version retired: documentId={} version={} by userId={}",
                documentId, versionNo, userId);
        return saved;
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

    private void notifySupabaseUploadAudit(Long tenantId, Long branchId, Long documentId,
                                            int versionNo, Long uploadedById, AppUser uploader,
                                            String originalFilename, String storagePath,
                                            String fileUrl, long fileSizeBytes, String mimeType) {
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("tenantId",         tenantId.toString());
            form.add("branchId",         branchId.toString());
            form.add("documentId",       documentId.toString());
            form.add("versionNo",        String.valueOf(versionNo));
            form.add("action",           "UPLOADED");
            form.add("performedById",    uploadedById.toString());
            if (uploader != null)        form.add("performedByName", uploader.getUsername());
            form.add("lifecycleState",   "DRAFT");
            form.add("fileUrl",          fileUrl != null ? fileUrl : "");
            form.add("storagePath",      storagePath != null ? storagePath : "");
            form.add("originalFilename", originalFilename != null ? originalFilename : "");
            form.add("fileSizeBytes",    String.valueOf(fileSizeBytes));
            form.add("mimeType",         mimeType);

            restClient.post()
                    .uri(supabaseUrl + "/functions/v1/document-lifecycle-audit")
                    .header("Authorization", "Bearer " + supabaseAnonKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Supabase upload audit notification failed: {}", e.getMessage());
        }
    }

    private void insertFileAudit(DocumentVersion version, String action, Long performedById) {
        try {
            AppUser performer = userRepository.findById(performedById).orElse(null);
            String performerName = performer != null ? performer.getUsername() : null;
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("tenantId",         version.getTenantId().toString());
            form.add("branchId",         version.getBranchId().toString());
            form.add("documentId",       version.getDocument().getId().toString());
            form.add("versionNo",        String.valueOf(version.getVersionNo()));
            form.add("action",           action);
            form.add("performedById",    performedById.toString());
            if (performerName != null)   form.add("performedByName", performerName);
            form.add("lifecycleState",   version.getLifecycleState());
            if (version.getFileUrl() != null)     form.add("fileUrl",     version.getFileUrl());
            if (version.getStoragePath() != null) form.add("storagePath", version.getStoragePath());

            restClient.post()
                    .uri(supabaseUrl + "/functions/v1/document-lifecycle-audit")
                    .header("Authorization", "Bearer " + supabaseAnonKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Audit failure is non-fatal; the main transaction continues
        }
    }

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
