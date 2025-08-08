package org.example.bookmarket.common.service;

public record S3UploadResponse(
        String url,
        String key
) {}