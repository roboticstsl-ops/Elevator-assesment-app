package com.tsl.rfsurvey;

import android.util.Base64;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Sends files / messages to a Telegram bot. Native so it dodges browser CORS. */
@CapacitorPlugin(name = "Telegram")
public class TelegramPlugin extends Plugin {

    @PluginMethod
    public void sendMessage(final PluginCall call) {
        final String token = call.getString("token", "");
        final String chatId = call.getString("chatId", "");
        final String text = call.getString("text", "");
        new Thread(() -> {
            try {
                String body = "chat_id=" + URLEncoder.encode(chatId, "UTF-8")
                            + "&text=" + URLEncoder.encode(text, "UTF-8");
                HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + token + "/sendMessage").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
                int code = c.getResponseCode();
                if (code == 200) call.resolve();
                else call.reject("Telegram HTTP " + code);
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void sendDocument(final PluginCall call) {
        final String token = call.getString("token", "");
        final String chatId = call.getString("chatId", "");
        final String b64 = call.getString("base64", "");
        final String filename = call.getString("filename", "report.docx");
        final String caption = call.getString("caption", "");
        new Thread(() -> {
            try {
                byte[] file = Base64.decode(b64, Base64.DEFAULT);
                String boundary = "----rfsurvey" + System.currentTimeMillis();
                String LF = "\r\n";
                HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + token + "/sendDocument").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream out = new DataOutputStream(c.getOutputStream());
                writeField(out, boundary, LF, "chat_id", chatId);
                if (!caption.isEmpty()) writeField(out, boundary, LF, "caption", caption);

                out.writeBytes("--" + boundary + LF);
                out.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" + filename + "\"" + LF);
                out.writeBytes("Content-Type: application/octet-stream" + LF + LF);
                out.write(file);
                out.writeBytes(LF);
                out.writeBytes("--" + boundary + "--" + LF);
                out.flush();
                out.close();

                int code = c.getResponseCode();
                if (code == 200) call.resolve();
                else call.reject("Telegram HTTP " + code);
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }).start();
    }

    private void writeField(DataOutputStream out, String boundary, String LF, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + LF);
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"" + LF + LF);
        out.write(value.getBytes("UTF-8"));
        out.writeBytes(LF);
    }
}
