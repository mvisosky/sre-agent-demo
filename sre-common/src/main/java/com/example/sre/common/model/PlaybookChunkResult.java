package com.example.sre.common.model;

import java.util.Map;

public record PlaybookChunkResult(
    String id,
    String serviceName,
    String title,
    String content,
    Map<String, Object> metadata
) {}
