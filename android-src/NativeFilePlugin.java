package com.tsl.rfsurvey;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Writes the report out twice: a copy in Downloads that the user actually
 * keeps, and a cache copy that backs the "open with..." chooser.
 *
 * The cache copy alone used to be the only one -- same filename every export,
 * so each report overwrote the one before it, and Android is free to clear
 * that directory whenever it wants space. Reports went missing that way.
 */
@CapacitorPlugin(name = "NativeFile")
public class NativeFilePlugin extends Plugin {

    @PluginMethod
    public void saveOpen(PluginCall call) {
        String b64 = call.getString("base64", "");
        String name = call.getString("name", "report.docx");
        String mime = call.getString("mime", "application/octet-stream");
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);

            // 1. the keeper copy, in the phone's Downloads folder
            String savedAt = saveToDownloads(name, mime, bytes);

            // 2. the cache copy, so the chooser has something to open
            File dir = new File(getContext().getCacheDir(), "exports");
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
            }
            Uri uri = FileProvider.getUriForFile(
                getContext(), getContext().getPackageName() + ".fileprovider", f);

            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(view, "Open report");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(chooser);

            JSObject res = new JSObject();
            res.put("savedAt", savedAt == null ? "" : savedAt);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("save/open failed: " + e.getMessage());
        }
    }

    /** Downloads/<name>, via MediaStore on Android 10+ (no permission needed). */
    private String saveToDownloads(String name, String mime, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri item = getContext().getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (item == null) return null;
                try (OutputStream os = getContext().getContentResolver().openOutputStream(item)) {
                    if (os != null) os.write(bytes);
                }
                v.clear();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                getContext().getContentResolver().update(item, v, null, null);
                return "Downloads/" + name;
            }
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dl.mkdirs();
            File out = new File(dl, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;   // keeper copy is a bonus; never fail the export over it
        }
    }
}
