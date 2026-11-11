package com.example.inventory.controller;

import com.example.inventory.support.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/receipt-templates")
public class ReceiptTemplateController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Map<String, Template> TEMPLATES = Map.of(
            "simple", new Template("receipt-template-simple.xlsx", "Simple"),
            "detailed", new Template("receipt-template-detailed.xlsx", "Detailed"),
            "with-logo", new Template("receipt-template-with-logo.xlsx", "With Logo"));

    @GetMapping
    public List<Map<String, String>> list() {
        return List.of(
                Map.of("id", "simple", "name", "Simple"),
                Map.of("id", "detailed", "name", "Detailed"),
                Map.of("id", "with-logo", "name", "With Logo"));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        Template template = TEMPLATES.get(id);
        if (template == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "api.template.notFound");
        }
        Resource resource = new ClassPathResource("receipt-templates/" + template.filename());
        if (!resource.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "api.template.missing");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + template.filename() + "\"")
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .body(resource);
    }

    private record Template(String filename, String name) {
    }
}
