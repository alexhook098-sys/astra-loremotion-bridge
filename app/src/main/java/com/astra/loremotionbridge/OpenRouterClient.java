package com.astra.loremotionbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;

/** OpenRouter client. API key is encrypted with an Android Keystore AES key. */
public final class OpenRouterClient {
    private static final String PREFS="astra_openrouter";
    private static final String KEY="encrypted_key";
    private static final String ALIAS="astra_openrouter_aes";
    private OpenRouterClient(){}

    private static SharedPreferences prefs(){ return MainApplication.context().getSharedPreferences(PREFS,Context.MODE_PRIVATE); }

    private static SecretKey secretKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(ks.containsAlias(ALIAS)) return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }

    public static void saveKey(String value) throws Exception {
        String key=value==null?"":value.trim();
        if(!key.startsWith("sk-or-") || key.length()<20) throw new IllegalArgumentException("Проверь API-ключ OpenRouter");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,secretKey());
        byte[] encrypted=c.doFinal(key.getBytes(StandardCharsets.UTF_8));
        String packed=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+"."+Base64.encodeToString(encrypted,Base64.NO_WRAP);
        prefs().edit().putString(KEY,packed).apply();
    }

    private static String readKey() throws Exception {
        String packed=prefs().getString(KEY,null); if(packed==null) return "";
        String[] parts=packed.split("\\.",2);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    public static boolean hasKey(){ try{return !readKey().isEmpty();}catch(Exception e){return false;} }
    public static void clearKey(){ prefs().edit().remove(KEY).apply(); }

    public static String chat(String prompt, JSONArray history, String model) {
        HttpURLConnection conn=null;
        try {
            String apiKey=readKey(); if(apiKey.isEmpty()) throw new IllegalStateException("Сначала добавь ключ OpenRouter");
            JSONObject body=new JSONObject(); body.put("model",model==null||model.trim().isEmpty()?"openrouter/free":model.trim());
            body.put("temperature",0.7); body.put("max_tokens",700);
            JSONArray messages=new JSONArray();
            messages.put(new JSONObject().put("role","system").put("content",
              "Ты — ASTRA, персональный помощник. Общайся по-русски, тепло и естественно, на «ты». "+
              "Выполняй просьбу напрямую, не пересказывай её. Не повторяй шаблонные фразы. "+
              "Если просят создать текст или промпт — сразу выдай готовый качественный результат. "+
              "Не выдумывай факты и не утверждай, что выполнила действия, которых не совершала."));
            int start=Math.max(0,history.length()-12);
            for(int i=start;i<history.length();i++){
                JSONObject item=history.optJSONObject(i); if(item==null) continue;
                String text=item.optString("text",""); if(text.isEmpty()) continue;
                messages.put(new JSONObject().put("role",item.optBoolean("user")?"user":"assistant").put("content",text));
            }
            if(messages.length()==1) messages.put(new JSONObject().put("role","user").put("content",prompt));
            body.put("messages",messages);
            conn=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
            conn.setRequestMethod("POST"); conn.setConnectTimeout(20000); conn.setReadTimeout(90000);
            conn.setDoOutput(true); conn.setRequestProperty("Authorization","Bearer "+apiKey);
            conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
            conn.setRequestProperty("HTTP-Referer","https://github.com/alexhook098-sys/astra-loremotion-bridge");
            conn.setRequestProperty("X-Title","ASTRA Browser");
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
            int status=conn.getResponseCode();
            InputStream stream=status>=200&&status<300?conn.getInputStream():conn.getErrorStream();
            StringBuilder response=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
                String line; while((line=reader.readLine())!=null) response.append(line);
            }
            JSONObject json=new JSONObject(response.toString());
            if(status<200||status>=300){
                String message=json.optJSONObject("error")!=null?json.optJSONObject("error").optString("message","HTTP "+status):"HTTP "+status;
                throw new IOException(message);
            }
            JSONArray choices=json.optJSONArray("choices");
            if(choices==null||choices.length()==0) throw new IOException("Провайдер не вернул ответ");
            JSONObject message=choices.getJSONObject(0).optJSONObject("message");
            return message==null?"Пустой ответ модели":message.optString("content","").trim();
        } catch(Exception e){return "Ошибка OpenRouter: "+String.valueOf(e.getMessage());}
        finally {if(conn!=null) conn.disconnect();}
    }
}
