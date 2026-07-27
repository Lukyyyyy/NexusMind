package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.service.KnowledgeGraphService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge-graph")
public class KnowledgeGraphController {
    private final KnowledgeGraphService graphService;

    public KnowledgeGraphController(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping("/documents/{fileMd5}")
    public ResponseEntity<?> get(@PathVariable String fileMd5,
                                 @RequestAttribute("userId") String userId,
                                 @RequestAttribute("role") String role) {
        return ok(graphService.get(fileMd5, userId, role));
    }

    @PutMapping("/documents/{fileMd5}/candidates/{candidateId}")
    public ResponseEntity<?> updateCandidate(@PathVariable String fileMd5, @PathVariable Long candidateId,
                                             @RequestAttribute("userId") String userId,
                                             @RequestAttribute("role") String role,
                                             @RequestBody KnowledgeGraphService.CandidateUpdate request) {
        return ok(graphService.updateCandidate(fileMd5, candidateId, userId, role, request));
    }

    @PostMapping("/documents/{fileMd5}/publish")
    public ResponseEntity<?> publish(@PathVariable String fileMd5,
                                     @RequestAttribute("userId") String userId,
                                     @RequestAttribute("role") String role) {
        return ok(graphService.publish(fileMd5, userId, role));
    }

    @PutMapping("/documents/{fileMd5}/enabled")
    public ResponseEntity<?> enabled(@PathVariable String fileMd5,
                                     @RequestAttribute("userId") String userId,
                                     @RequestAttribute("role") String role,
                                     @RequestBody KnowledgeGraphService.EnabledRequest request) {
        return ok(graphService.setEnabled(fileMd5, userId, role, request.enabled()));
    }

    @PostMapping("/documents/{fileMd5}/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable String fileMd5,
                                     @RequestAttribute("userId") String userId,
                                     @RequestAttribute("role") String role) {
        return ok(graphService.rebuild(fileMd5, userId, role));
    }

    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<?> custom(CustomException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(),
                "message", e.getMessage(), "data", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> error(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500,
                "message", e.getMessage() == null ? "知识图谱操作失败" : e.getMessage(), "data", Map.of()));
    }
}
