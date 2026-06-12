package com.deskit.deskit.ai.chatbot.openai.service;

import com.deskit.deskit.ai.chatbot.openai.entity.ChatInfo;
import com.deskit.deskit.ai.chatbot.openai.repository.ChatRepository;
import com.deskit.deskit.ai.chatbot.rag.dto.ChatResponse;
import com.deskit.deskit.ai.chatbot.rag.service.ChatSaveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Log4j2
@RequiredArgsConstructor
public class OpenAIService {

    @Value("${spring.ai.google.genai.chat.options.model}")
    private String chatModel;

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModelClient;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatRepository chatRepository;
    private final ChatSaveService chatSaveService;
    private final ConversationService conversationService;

    private static final int MIN_TEXT_LENGTH = 6;
    private static final String TOO_SHORT_MESSAGE =
            "Please share a little more detail so I can help you accurately.";
    private static final String OPENAI_ERROR_MESSAGE =
            "The assistant is temporarily unavailable. Please try again in a moment.";

    public ChatResponse generate(Long memberId, String text) {
        ChatInfo chatInfo = conversationService.getOrCreateActiveConversation(memberId);

        List<Message> messages = new ArrayList<>();

        messages.add(new SystemMessage(
                """
                        당신은 고객지원을 돕는 AI 상담 챗봇입니다.

                        고객이 문의를 자세히 설명하도록 "~에 대해 알려줘" 같은 형식으로 유도해 주세요.

                        질문 길이가 너무 짧으면(6글자 이하) 아래처럼 안내해 주세요.
                        - 조금 더 구체적으로 상황을 설명해 주세요.
                        - 질문이 이해되지 않으면 다시 한 번 설명해 주세요.
                        """
        ));

        messages.add(new UserMessage(text));

        String answer;
        try {
            Prompt prompt = new Prompt(messages, GoogleGenAiChatOptions.builder()
                    .model(chatModel)
                    .temperature(0.7)
                    .build());
            answer = chatModelClient.call(prompt).getResult().getOutput().getText();
        } catch (RuntimeException e) {
            log.error("Gemini chat error", e);
            return new ChatResponse(
                    "현재 상담 시스템에 문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
                    List.of(),
                    false
            );
        }
        chatSaveService.saveChat(chatInfo.getChatId(), text, answer);
        chatSaveService.saveChatMemory(String.valueOf(memberId), text, chatMemoryRepository);

        return new ChatResponse(answer, List.of(), false);
    }

    public List<float[]> generateEmbedding(List<String> texts, String model) {
        EmbeddingResponse response = embeddingModel.embedForResponse(texts);
        return response.getResults().stream()
                .map(Embedding::getOutput)
                .toList();
    }

    private boolean isTooShort(String text) {
        return text == null || text.trim().length() < MIN_TEXT_LENGTH;
    }

    private ChatResponse buildShortResponse(String text) {
        if (text == null || text.isBlank()) {
            return ChatResponse.builder()
                    .answer("Please enter a question.")
                    .sources(List.of())
                    .escalated(false)
                    .build();
        }
        return ChatResponse.builder()
                .answer(TOO_SHORT_MESSAGE)
                .sources(List.of())
                .escalated(false)
                .build();
    }

    private ChatResponse buildErrorResponse() {
        return ChatResponse.builder()
                .answer(OPENAI_ERROR_MESSAGE)
                .sources(List.of())
                .escalated(false)
                .build();
    }

    private String normalize(String text) {
        return text == null ? null : text.trim();
    }
}
