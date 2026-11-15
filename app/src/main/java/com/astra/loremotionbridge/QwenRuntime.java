package com.astra.loremotionbridge;

import org.json.JSONObject;
import java.io.File;

/**
 * Optional on-device Qwen runtime backed by llama.cpp.
 * The GGUF model is stored in app-private files, never bundled in the APK.
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

    private static synchronized void ensureInitialized() {
        if (initialized) return;
        System.loadLibrary("ai-chat");
        init(MainApplication.context().getApplicationInfo().nativeLibraryDir);
        initialized = true;
    }

    public static synchronized String statusJson() {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("engine", "llama.cpp");
            out.put("initialized", initialized);
            out.put("loaded", loaded);
            out.put("model", modelPath);
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
                return "{\"ok\":false,\"error\":\"missing_path\",\"hint\":\"POST /qwen/load with JSON {path: absolute GGUF path}\"}";
            }
            File file = new File(path);
            if (!file.isFile() || !file.canRead()) {
                return "{\"ok\":false,\"error\":\"model_file_not_readable\"}";
            }
            ensureInitialized();
            if (loaded) {
                unload();
                loaded = false;
            }
            int result = load(file.getAbsolutePath());
            if (result != 0) throw new IllegalStateException("llama_model_load_failed:" + result);
            result = prepare();
            if (result != 0) throw new IllegalStateException("llama_context_prepare_failed:" + result);
            result = processSystemPrompt("Ты — локальный помощник ASTRA. Отвечай кратко, точно и на языке пользователя. Если не знаешь — скажи об этом.");
            if (result != 0) throw new IllegalStateException("system_prompt_failed:" + result);
            loaded = true;
            modelPath = file.getAbsolutePath();
            lastError = "";
            return "{\"ok\":true,\"loaded\":true,\"model\":\"" + escape(modelPath) + "\"}";
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            return "{\"ok\":false,\"error\":\"" + escape(lastError) + "\"}";
        }
    }

    public static synchronized String chat(String prompt, int maxTokens) {
        try {
            if (!loaded) return "{\"ok\":false,\"error\":\"model_not_loaded\",\"hint\":\"Load a GGUF model with POST /qwen/load first\"}";
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
