package com.astra.loremotionbridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;

/**
 * Persistent on-device Qwen runtime backed by llama.cpp.
 * The GGUF file is copied once into app-private storage and survives app updates.
 */
public final class QwenRuntime {
    private static boolean initialized = false;
    private static boolean loaded = false;
    private static String modelPath = "";
    private static String lastError = "";

    private QwenRuntime() {}

    private static native void init(String nativeLibDir);
    private static native int load(String modelPath);
    private static native int prepare();
    private static native int processSystemPrompt(String systemPrompt);
    private static native int processUserPrompt(String prompt, int predictLength);
    private static native String generateNextToken();
    private static native void unload();
    private static native void shutdown();
    private static native String systemInfo();

    private static File modelFile() {
        Context context = MainApplication.context();
        File directory = new File(context.getFilesDir(), "models");
        if (!directory.exists()) directory.mkdirs();
        return new File(directory, "qwen-model.gguf");
    }

    public static synchronized boolean hasStoredModel() {
        File file = modelFile();
        return file.isFile() && file.length() > 0;
    }

    public static synchronized String storedModelPath() {
        return modelFile().getAbsolutePath();
    }

    public static synchronized boolean isLoaded() {
        return loaded;
    }

    private static synchronized void ensureInitialized() {
        if (initialized) return;
        System.loadLibrary("ai-chat");
        init(MainApplication.context().getApplicationInfo().nativeLibraryDir);
        initialized = true;
    }

    /** Automatically reloads the already-imported GGUF after process restart. */
    public static synchronized String loadStoredModel() {
        File file = modelFile();
        if (!file.isFile() || file.length() == 0) {
            return "{\"ok\":false,\"error\":\"model_not_installed\"}";
        }
        if (loaded && file.getAbsolutePath().equals(modelPath)) {
            return "{\"ok\":true,\"loaded\":true,\"already_loaded\":true}";
        }
        return loadModel(file.getAbsolutePath());
    }

    public static synchronized String statusJson() {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("engine", "llama.cpp");
            out.put("initialized", initialized);
            out.put("loaded", loaded);
            out.put("installed", hasStoredModel());
            out.put("model", modelPath);
            out.put("stored_model", storedModelPath());
            out.put("last_error", lastError);
            if (initialized) out.put("system_info", systemInfo());
            return out.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"status_failed\"}";
        }
    }

    public static synchronized String loadModel(String path) {
        try {
            if (path == null || path.trim().isEmpty()) {
                return "{\"ok\":false,\"error\":\"missing_path\"}";
            }
            File file = new File(path);
            if (!file.isFile() || !file.canRead() || file.length() == 0) {
                return "{\"ok\":false,\"error\":\"model_file_not_readable\"}";
            }
            if (loaded) {
                unload();
                loaded = false;
            }
            ensureInitialized();
            int result = load(file.getAbsolutePath());
            if (result != 0) throw new IllegalStateException("llama_model_load_failed:" + result);
            result = prepare();
            if (result != 0) throw new IllegalStateException("llama_context_prepare_failed:" + result);
            result = processSystemPrompt("Ты — языковое ядро персонального помощника ASTRA. Пользователь — один человек, общайся с ним естественно, тепло и на «ты». Всегда отвечай на русском, если пользователь не попросил другой язык. Выполняй запрос напрямую. Не повторяй вопрос и не копируй собственные предыдущие фразы. Для просьбы создать промпт выдай готовый оригинальный промпт с конкретными визуальными деталями, композицией, светом, цветом и атмосферой; не пиши вместо этого вводную фразу. Если данных недостаточно, задай один короткий уточняющий вопрос. Не заявляй, что выполнила действие, если оно не выполнено. Не выдумывай память или факты.");
            if (result != 0) throw new IllegalStateException("system_prompt_failed:" + result);
            loaded = true;
            modelPath = file.getAbsolutePath();
            lastError = "";
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("loaded", true);
            out.put("model", modelPath);
            return out.toString();
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            loaded = false;
            return "{\"ok\":false,\"error\":\"" + escape(lastError) + "\"}";
        }
    }

    public static synchronized String chat(String prompt, int maxTokens) {
        try {
            if (!loaded) return "{\"ok\":false,\"error\":\"model_not_loaded\"}";
            if (prompt == null || prompt.trim().isEmpty()) return "{\"ok\":false,\"error\":\"empty_prompt\"}";
            int count = Math.max(1, Math.min(512, maxTokens));
            int result = processUserPrompt(prompt, count);
            if (result != 0) throw new IllegalStateException("prompt_failed:" + result);
            StringBuilder answer = new StringBuilder();
            String token;
            while ((token = generateNextToken()) != null) {
                if (answer.length() < 16000) answer.append(token);
                else break;
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("model", modelPath);
            out.put("response", answer.toString());
            return out.toString();
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            return "{\"ok\":false,\"error\":\"" + escape(lastError) + "\"}";
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
