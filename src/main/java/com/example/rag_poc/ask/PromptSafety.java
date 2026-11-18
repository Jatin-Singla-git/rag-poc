package com.example.rag_poc.ask;

import java.util.regex.Pattern;

final class PromptSafety {

    private static final Pattern PROMPT_TAG =
            Pattern.compile("<(\\s*/?\\s*(?:context|candidates|sample_row)\\b)", Pattern.CASE_INSENSITIVE);

    private PromptSafety() {
    }

    /** Turns the "&lt;" of a prompt delimiter tag into "&amp;lt;" so it can no longer act as a delimiter. */
    static String untrusted(String text) {
        return text == null ? null : PROMPT_TAG.matcher(text).replaceAll("&lt;$1");
    }
}
