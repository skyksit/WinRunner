package com.winlator.core;

import android.app.Activity;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class HttpUtils {
    private static HttpURLConnection openConnection(String url) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection)(new URL(url)).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        return connection;
    }

    private static void downloadAsync(String url, Callback<String> onDownloadComplete) {
        try {
            HttpURLConnection connection = openConnection(url);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                onDownloadComplete.call(null);
                return;
            }

            byte[] bytes;
            try (InputStream inStream = connection.getInputStream()) {
                bytes = StreamUtils.copyToByteArray(inStream);
            }
            onDownloadComplete.call(new String(bytes, StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            onDownloadComplete.call(null);
        }
    }

    public static void download(final String url, final Callback<String> onDownloadComplete) {
        Executors.newSingleThreadExecutor().execute(() -> downloadAsync(url, onDownloadComplete));
    }

    private static void downloadAsync(String url, File destination, AtomicBoolean interruptRef, Callback<Integer> onPublishProgress, Callback<Boolean> onDownloadComplete) {
        try {
            interruptRef.set(false);
            HttpURLConnection connection = openConnection(url);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                onDownloadComplete.call(false);
                return;
            }

            long contentLength = connection.getContentLengthLong();
            try (InputStream inStream = new BufferedInputStream(connection.getInputStream(), StreamUtils.BUFFER_SIZE);
                 OutputStream outStream = new FileOutputStream(destination)) {

                byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
                long totalSize = 0;
                int bytesRead;
                int lastProgress = -1;
                while ((bytesRead = inStream.read(buffer)) != -1 && !interruptRef.get()) {
                    totalSize += bytesRead;
                    if (onPublishProgress != null && contentLength > 0) {
                        // Only on change: a 150 MB APK would otherwise post thousands of UI updates.
                        int progress = (int)((totalSize * 100) / contentLength);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            onPublishProgress.call(progress);
                        }
                    }
                    outStream.write(buffer, 0, bytesRead);
                }

            }

            onDownloadComplete.call(!interruptRef.get());
        }
        catch (Exception e) {
            onDownloadComplete.call(false);
        }
    }

    public static void download(final Activity activity, final String url, final File destination, final Callback<Boolean> onDownloadComplete) {
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        final AtomicBoolean interruptRef = new AtomicBoolean();
        dialog.show(() -> interruptRef.set(true));
        Executors.newSingleThreadExecutor().execute(() -> {
            downloadAsync(url, destination, interruptRef, (progress) -> {
                activity.runOnUiThread(() -> {
                    dialog.setProgress(progress);
                });
            }, (success) -> {
                if (!success && destination.isFile()) destination.delete();
                activity.runOnUiThread(() -> {
                    dialog.close();
                    onDownloadComplete.call(success);
                });
            });
        });
    }
}
