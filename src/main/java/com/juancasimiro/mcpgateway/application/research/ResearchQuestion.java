package com.juancasimiro.mcpgateway.application.research;

import com.juancasimiro.mcpgateway.application.research.exception.InvalidResearchQuestionException;

public record ResearchQuestion(
        String question,
        int resultCount) {

    private static final int MAX_QUESTION_LENGTH = 1_000;
    private static final int MIN_RESULT_COUNT = 1;
    private static final int MAX_RESULT_COUNT = 20;

    public ResearchQuestion {
        if (question == null) {
            throw new InvalidResearchQuestionException("The research question is required.");
        }

        question = question.trim();
        if (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH) {
            throw new InvalidResearchQuestionException(
                    "The research question must contain between 1 and 1,000 characters."
            );
        }

        if (resultCount < MIN_RESULT_COUNT || resultCount > MAX_RESULT_COUNT) {
            throw new InvalidResearchQuestionException(
                    "The result count must be between 1 and 20."
            );
        }
    }
}
