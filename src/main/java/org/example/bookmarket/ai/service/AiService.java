package org.example.bookmarket.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.bookmarket.ai.dto.PriceSuggestResponse;
import org.example.bookmarket.common.handler.exception.CustomException;
import org.example.bookmarket.common.handler.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.endpoint}")
    private String apiEndpoint;

    public PriceSuggestResponse suggestPriceFromImage(String imageUrl, int newPrice) throws IOException {
        String prompt = "당신은 중고책의 결함을 찾아내는 전문 분석가입니다. 이 사진을 보고 아래 '결함 목록' 중에서 해당하는 것을 모두 찾아서 키워드 리스트 형태로 알려주세요. 결함이 전혀 없으면 '없음'이라고 답해주세요. 결함 목록: [모서리 닳음, 커버 긁힘, 페이지 찢어짐, 물에 젖은 자국, 낙서 또는 필기, 변색 또는 황변, 스티커 부착] 결과는 반드시 '결함: [키워드1, 키워드2, ...]' 형식으로만 대답해주세요.";

        byte[] imageBytes;
        try (InputStream inputStream = new URL(imageUrl).openStream()) {
            imageBytes = inputStream.readAllBytes();
        }
        String base64EncodedImage = Base64.getEncoder().encodeToString(imageBytes);

        String requestBody = String.format("""
            {
              "contents": [
                {
                  "parts": [
                    { "text": "%s" },
                    { "inline_data": { "mime_type": "image/jpeg", "data": "%s" } }
                  ]
                }
              ]
            }
            """, prompt, base64EncodedImage);

        String analysisResultText = callGeminiApi(requestBody);

        List<String> defects = parseDefects(analysisResultText);
        int penalty = calculatePenalty(defects);
        double finalRatio = (100.0 - penalty) / 100.0;
        int suggestedMaxPrice = (int) (newPrice * finalRatio * 0.8 / 100.0) * 100;
        int suggestedMinPrice = (int) (suggestedMaxPrice * 0.85 / 100.0) * 100;

        return new PriceSuggestResponse(defects, suggestedMinPrice, suggestedMaxPrice);
    }

    public List<String> extractKeywordsFromQuery(String userQuery) throws IOException {
        String prompt = String.format("다음 사용자 검색어에서 도서 검색에 사용할 핵심 키워드를 1~3개 추출해서 쉼표로 구분된 목록 형태로만 반환해줘. 다른 부가적인 설명은 절대로 추가하지 마. 사용자 검색어: \\\"%s\\\"", userQuery);
        String requestBody = createTextOnlyRequestBody(prompt);
        String resultText = callGeminiApi(requestBody);

        if (resultText.isEmpty()) {
            return Collections.emptyList();
        }
        return List.of(resultText.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * [수정] 기존 동기 방식 메소드는 private으로 변경하여 내부적으로만 사용합니다.
     */
    private String summarizeBook(String bookInfo) throws IOException {
        String prompt = String.format(
                "다음 책 정보를 바탕으로, 이 책이 어떤 내용을 다루고 있는지 핵심만 간결하게 3~4문장으로 요약해줘. " +
                        "친절하고 상냥한 서점 주인의 말투로 설명해줘. 정보: \\\"%s\\\"",
                bookInfo
        );
        String requestBody = createTextOnlyRequestBody(prompt);
        return callGeminiApi(requestBody);
    }

    /**
     * [신규] @Async를 사용하여 책 요약 기능을 비동기적으로 호출하는 메소드
     */
    @Async
    public CompletableFuture<String> summarizeBookAsync(String bookInfo) {
        try {
            return CompletableFuture.completedFuture(summarizeBook(bookInfo));
        } catch (IOException e) {
            log.error("비동기 책 요약 중 오류 발생", e);
            return CompletableFuture.completedFuture(""); // 오류 발생 시 빈 문자열 반환
        }
    }

    /**
     * [수정] 기존 동기 방식 메소드는 private으로 변경
     */
    private String reviewWithPersona(String bookInfo) throws IOException {
        String prompt = String.format(
                "당신은 '긍정적이고 통찰력 있는 책 애호가'라는 페르소나를 가지고 있어. " +
                        "다음 책 정보를 바탕으로, 이 책에 대한 당신의 감상과 어떤 사람들에게 이 책을 추천하고 싶은지 리뷰를 작성해줘. " +
                        "결과는 '### 리뷰\\n...'와 '### 이런 분들께 추천해요\\n...' 형식으로 구분해서 작성해줘. 정보: \\\"%s\\\"",
                bookInfo
        );
        String requestBody = createTextOnlyRequestBody(prompt);
        return callGeminiApi(requestBody);
    }

    /**
     * [신규] @Async를 사용하여 페르소나 리뷰 기능을 비동기적으로 호출하는 메소드
     */
    @Async
    public CompletableFuture<String> reviewWithPersonaAsync(String bookInfo) {
        try {
            return CompletableFuture.completedFuture(reviewWithPersona(bookInfo));
        } catch (IOException e) {
            log.error("비동기 페르소나 리뷰 중 오류 발생", e);
            return CompletableFuture.completedFuture(""); // 오류 발생 시 빈 문자열 반환
        }
    }

    private String createTextOnlyRequestBody(String prompt) {
        return String.format("""
            {
              "contents": [ { "parts": [ { "text": "%s" } ] } ]
            }
            """, prompt);
    }

    private String callGeminiApi(String requestBody) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        String urlWithKey = apiEndpoint + "?key=" + apiKey;

        try {
            String response = restTemplate.postForObject(urlWithKey, entity, String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode textNode = root.at("/candidates/0/content/parts/0/text");
            return textNode.asText();

        } catch (RestClientException e) {
            log.error("Gemini API 호출 중 오류 발생", e);
            throw new CustomException(ErrorCode.AI_ANALYSIS_FAILED);
        }
    }

    private List<String> parseDefects(String text) {
        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (line.trim().startsWith("결함:")) {
                Pattern pattern = Pattern.compile("\\[(.*?)\\]");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String defectString = matcher.group(1).trim();
                    if (defectString.equalsIgnoreCase("없음") || defectString.isEmpty()) {
                        return Collections.emptyList();
                    }
                    return List.of(defectString.split(",")).stream()
                            .map(String::trim)
                            .collect(Collectors.toList());
                }
            }
        }
        log.warn("AI 응답에서 '결함:' 키워드를 찾지 못했습니다. 전체 응답: {}", text);
        return List.of("결함 정보 없음");
    }

    private int calculatePenalty(List<String> defects) {
        int totalPenalty = 0;
        for (String defect : defects) {
            switch (defect) {
            case "페이지 찢어짐", "물에 젖은 자국" -> totalPenalty += 30;
            case "낙서 또는 필기", "변색 또는 황변" -> totalPenalty += 20;
            case "모서리 닳음", "커버 긁힘", "스티커 부착" -> totalPenalty += 10;
            }
        }
        return Math.min(100, totalPenalty);
    }
}