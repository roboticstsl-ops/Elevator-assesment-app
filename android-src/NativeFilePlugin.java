package com.tsl.rfsurvey;

import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;

/** Writes a base64 blob to the cache dir and opens it with the system chooser. */
@CapacitorPlugin(name = "NativeFile")
public class NativeFilePlugin extends Plugin {

    @PluginMethod
    public void saveOpen(PluginCall call) {
        String b64 = call.getString("base64", "");
        String name = call.getString("name", "report.docx");
        String mime = call.getString("mime", "application/octet-stream");
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
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
            call.resolve();
        } catch (Exception e) {
            call.reject("save/open failed: " + e.getMessage());
        }
    }
}
