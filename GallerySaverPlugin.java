package com.capcut.editor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Receives a video/image from the web page in base64 chunks and saves it
 * into the phone gallery (Movies/CapCutEditor or Pictures/CapCutEditor).
 * JS side: begin() -> append({data}) x N -> finish({name, mime})
 */
@CapacitorPlugin(name = "GallerySaver")
public class GallerySaverPlugin extends Plugin {

    private File tmp;
    private FileOutputStream out;

    @PluginMethod
    public void begin(PluginCall call) {
        try {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
            tmp = new File(getContext().getCacheDir(), "export.tmp");
            out = new FileOutputStream(tmp, false);
            call.resolve();
        } catch (Exception e) {
            call.reject("begin failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void append(PluginCall call) {
        try {
            if (out == null) {
                call.reject("begin() was not called");
                return;
            }
            String data = call.getString("data", "");
            if (data != null && !data.isEmpty()) {
                out.write(Base64.decode(data, Base64.DEFAULT));
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("append failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void finish(PluginCall call) {
        try {
            if (out == null || tmp == null) {
                call.reject("begin() was not called");
                return;
            }
            out.flush();
            out.close();
            out = null;

            String name = call.getString("name", "video.mp4");
            String mime = call.getString("mime", "video/mp4");
            boolean isVideo = mime.startsWith("video");

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                call.reject("Android 10 or newer is required to save to the gallery");
                return;
            }

            String folder = (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/CapCutEditor";
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = isVideo
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

            ContentResolver resolver = getContext().getContentResolver();
            Uri item = resolver.insert(collection, values);
            if (item == null) {
                call.reject("Could not create gallery entry");
                return;
            }

            try (InputStream in = new FileInputStream(tmp);
                 OutputStream os = resolver.openOutputStream(item)) {
                if (os == null) throw new Exception("Could not open gallery file");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (Exception e) {
                resolver.delete(item, null, null);
                throw e;
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, done, null, null);
            tmp.delete();

            call.resolve();
        } catch (Exception e) {
            call.reject("finish failed: " + e.getMessage());
        }
    }
}
