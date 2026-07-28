package dev.kevindubois.rollout.agent.service;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Enables sendThinking/returnThinking on the native Gemini client used for the
 * remediation model, so it correctly round-trips the per-tool-call
 * thought_signature Gemini requires across multi-turn tool use (see
 * GeminiCompatibleModelCustomizer's javadoc and langchain4j/langchain4j#4097
 * for why the generic OpenAiChatModel cannot do this at all).
 */
@ApplicationScoped
@ModelName("remediation")
public class GeminiThinkingSignatureCustomizer
        implements ModelBuilderCustomizer<GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder> {

    @Override
    public void customize(GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder) {
        builder.returnThinking(true).sendThinking(true);
    }
}
