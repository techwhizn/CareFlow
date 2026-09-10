package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class DocumentTransferController {
  private final DocumentUploadService uploads;
  private final DocumentReadService reads;

  public DocumentTransferController(DocumentUploadService uploads, DocumentReadService reads) {
    this.uploads = uploads;
    this.reads = reads;
  }

  @PostMapping("/knowledge-bases/{id}/documents")
  public Object upload(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("Authorization") String authorization,
      @RequestParam MultipartFile file,
      @RequestParam(required = false) Long billing_revision)
      throws Exception {
    return uploads.upload(actor, authorization, id, null, key, file, billing_revision);
  }

  @PostMapping("/documents/{id}/versions")
  public Object replace(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("Authorization") String authorization,
      @RequestParam MultipartFile file,
      @RequestParam(required = false) Long billing_revision)
      throws Exception {
    return uploads.replace(actor, authorization, id, key, file, billing_revision);
  }

  @GetMapping("/document-versions/{id}/source")
  public ResponseEntity<byte[]> download(@RequestAttribute Actor actor, @PathVariable String id) {
    var source = reads.download(actor, id);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(source.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(source.content());
  }
}
