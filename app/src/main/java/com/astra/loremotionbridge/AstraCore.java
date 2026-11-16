package com.astra.loremotionbridge;

import java.util.Locale;

/** Lightweight local coordinator: handles simple intents and enriches LLM requests. */
public final class AstraCore {
    private AstraCore() {}

    /** Returns an immediate local answer, or null when the request needs the language model. */
    public static String handleLocal(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        String fact = null;
        if (lower.startsWith("запомни:")) fact = text.substring("запомни:".length()).trim();
        else if (lower.startsWith("запомни ")) fact = text.substring("запомни ".length()).trim();
        if (fact != null) {
            return AstraMemory.remember(fact)
                    ? "Запомнила. Сохранила это в личной памяти ASTRA на устройстве. ✦"
                    : "Не получилось сохранить. Заметка должна быть непустой и не длиннее 500 символов.";
        }

        if (lower.equals("что ты помнишь обо мне") || lower.equals("покажи память") ||
                lower.equals("что ты запомнила")) {
            String memory = AstraMemory.summary();
            return memory.isEmpty()
                    ? "Пока моя личная память пуста. Можешь написать: «Запомни: …»."
                    : "Вот что я сохранила в личной памяти:\n" + memory;
        }

        if (lower.equals("очисти память") || lower.equals("удали свою память")) {
            AstraMemory.clear();
            return "Личная память ASTRA очищена на этом устройстве.";
        }

        if (isGreeting(lower)) return "Привет! ❤️ Рада тебя видеть. Чем займёмся сегодня?";
        return null;
    }

    public static String preparePrompt(String userText) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Задача пользователя: ").append(userText.trim());
        String memory = AstraMemory.summary();
        if (!memory.isEmpty()) {
            prompt.append("\n\nЛИЧНАЯ ПАМЯТЬ ASTRA (используй только если относится к задаче):\n")
                    .append(memory);
        }
        prompt.append("\n\nПравила ответа: отвечай по-русски, естественно и по-дружески, на «ты». ")
                .append("Сразу выполняй просьбу, а не пересказывай её. Не повторяй свой предыдущий ответ. ")
                .append("Если просят придумать промпт для изображения — создай сам готовый подробный промпт, ")
                .append("а не объясняй, что можешь его написать. Не выдумывай факты о пользователе. ")
                .append("Пиши содержательно, но без лишней воды.");
        return prompt.toString();
    }

    private static boolean isGreeting(String text) {
        return text.matches("^(привет|здравствуй|здравствуйте|доброе утро|добрый день|добрый вечер|хай|салют)[!. ,]*$");
    }
}
