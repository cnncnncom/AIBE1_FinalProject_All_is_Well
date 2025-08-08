package org.example.bookmarket.ai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.bookmarket.ai.dto.PriceSuggestResponse;
import org.example.bookmarket.ai.service.AiService;
import org.example.bookmarket.common.handler.exception.CustomException;
import org.example.bookmarket.common.handler.exception.ErrorCode;
import org.example.bookmarket.common.service.S3UploadResponse; // ★ import 추가
import org.example.bookmarket.common.service.S3UploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final S3UploadService s3UploadService;

    @PostMapping("/suggest-price-from-upload")
    public ResponseEntity<PriceSuggestResponse> uploadAndSuggestPrice(
            @RequestParam("image") MultipartFile image,
            @RequestParam("newPrice") int newPrice) {

        if (image.isEmpty()) {
            log.warn("AI 가격 제안 요청에 이미지 파일이 누락되었습니다.");
            throw new CustomException(ErrorCode.INVALID_REQUEST, "AI 분석을 위한 이미지가 필요합니다.");
        }
        try {
            S3UploadResponse s3response = s3UploadService.upload(image, "temp-book-images");
            PriceSuggestResponse response = aiService.suggestPriceFromImage(s3response.key(), newPrice);

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("AI 이미지 분석 서비스 호출 중 IO 오류가 발생했습니다.", e);
            throw new CustomException(ErrorCode.AI_ANALYSIS_FAILED);
        }
    }
}