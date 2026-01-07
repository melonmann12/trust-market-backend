package com.trustmarket.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustmarket.game.model.game.Question;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class AIService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.url:}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Question generateQuestion(String topic) {
        // Fallback nhanh nếu không có key
        if (apiKey == null || apiKey.length() < 10) {
            log.warn("⚠️ Không tìm thấy API Key. Dùng câu hỏi mẫu.");
            return getMockQuestion();
        }

        try {
            String finalUrl = apiUrl + "?key=" + apiKey;
            String promptText = "Tạo 1 câu hỏi trắc nghiệm thú vị về chủ đề '" + topic + "' bằng tiếng Việt. " +
                    "Output JSON RAW: { \"content\": \"...\", \"options\": [\"A. ...\", \"B. ...\", \"C. ...\", \"D. ...\"], \"correctAnswer\": \"A\", \"explanation\": \"...\" }";

            Map<String, Object> contentPart = Map.of("text", promptText);
            Map<String, Object> parts = Map.of("parts", Collections.singletonList(contentPart));
            Map<String, Object> requestBody = Map.of("contents", Collections.singletonList(parts));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // Gọi API
            ResponseEntity<String> response = restTemplate.postForEntity(finalUrl, entity, String.class);
            return parseGeminiResponse(response.getBody());

        } catch (Exception e) {
            // QUAN TRỌNG: Nếu lỗi, log ra và TRẢ VỀ CÂU HỎI GIẢ NGAY LẬP TỨC
            log.error("❌ AI Service Lỗi: {} -> Dùng câu hỏi mẫu để game tiếp tục.", e.getMessage());
            return getMockQuestion();
        }
    }

    private Question parseGeminiResponse(String jsonResponse) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            String rawText = rootNode.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            String cleanJson = rawText.replace("```json", "").replace("```", "").trim();
            Question q = objectMapper.readValue(cleanJson, Question.class);
            q.setId(UUID.randomUUID().toString());
            return q;
        } catch (Exception e) {
            log.error("❌ Lỗi parse JSON AI: {}", e.getMessage());
            return getMockQuestion();
        }
    }

    // Câu hỏi dự phòng (Phao cứu sinh)
    private Question getMockQuestion() {
        return Question.builder()
                .id(UUID.randomUUID().toString())
                .content("Bitcoin được tạo ra bởi ai? (Câu hỏi dự phòng do AI đang bận)")
                .options(Arrays.asList("A. Satoshi Nakamoto", "B. Elon Musk", "C. Vitalik Buterin", "D. Mark Zuckerberg"))
                .correctAnswer("A")
                .explanation("Satoshi Nakamoto là bút danh của người sáng lập Bitcoin.")
                .build();
    }
}