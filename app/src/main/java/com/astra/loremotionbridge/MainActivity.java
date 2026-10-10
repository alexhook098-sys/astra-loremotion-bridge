package com.astra.loremotionbridge;

import android.app.Activity;
import android.media.projection.MediaProjectionManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int SCREEN_CAPTURE_REQUEST = 9001;

    private TextView title;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 40, 40, 40);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        title = new TextView(this);
        title.setText(
                "ASTRA Browser Control\n\n" +
                "Local API: 127.0.0.1:18765"
        );
        title.setTextSize(20);
        box.addView(title);

        Button accessibility = new Button(this);
        accessibility.setText("ОТКРЫТЬ СПЕЦИАЛЬНЫЕ ВОЗМОЖНОСТИ");
        accessibility.setOnClickListener(v ->
                startActivity(
                        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
        );
        box.addView(accessibility);

        Button start = new Button(this);
        start.setText("ЗАПУСТИТЬ ЛОКАЛЬНЫЙ МОСТ");

        start.setOnClickListener(v -> {
            try {
                Intent intent =
                        new Intent(this, BrowserBridgeService.class);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    LocalBridgeServer.start();
                } else {
                    startService(intent);
                }

                ProrootRuntime.prepare(this);
                ProrootRuntime.extractRootfs(this);

                String prorootTest = runProrootTest();

                title.setText(
                        "ASTRA Browser Control\n\n" +
                        "Proroot extracted: " +
                        (ProrootRuntime.checkExtracted(this) ? "OK ✓" : "FAILED ✗") +
                        "\n\n" +
                        "Proroot test: " + prorootTest +
                        "\n\n" +
                        "127.0.0.1:18765"
                );

                new Handler(Looper.getMainLooper()).postDelayed(() -> {

                    if (LocalBridgeServer.isRunning()) {

                        title.setText(
                                "ASTRA Browser Control\n\n" +
                                "SERVER ONLINE ✓\n\n" +
                                "127.0.0.1:18765"
                        );

                    } else {

                        String error = LocalBridgeServer.getLastError();

                        if (error == null || error.isEmpty()) {
                            error = "Сервер не запустился, причина не определена";
                        }

                        title.setText(
                                "ASTRA Browser Control\n\n" +
                                "SERVER ERROR\n\n" +
                                error
                        );
                    }

                }, 1000);

            } catch (Exception e) {
                String message =
                        e.getMessage() == null
                                ? "без сообщения"
                                : e.getMessage();

                title.setText(
                        "ASTRA Browser Control\n\n" +
                        "ОШИБКА ЗАПУСКА:\n\n" +
                        e.getClass().getName() +
                        "\n\n" +
                        message
                );
            }
        });

        box.addView(start);

        Button screen = new Button(this);
        screen.setText("РАЗРЕШИТЬ ЗАХВАТ ЭКРАНА");
        screen.setOnClickListener(v -> {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

            startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    SCREEN_CAPTURE_REQUEST
            );
        });
        box.addView(screen);

        Button open = new Button(this);
        open.setText("ОТКРЫТЬ LOREMOTION В VIVALDI");
        open.setOnClickListener(v ->
                LocalBridgeServer.openUrl(
                        "https://loremotion.com/generate/"
                )
        );
        box.addView(open);

        setContentView(box);
    }

    private String hasProrootInApk() {
        try {
            java.util.zip.ZipFile zip =
                    new java.util.zip.ZipFile(getApplicationInfo().sourceDir);

            String[] names = {
                    "lib/arm64-v8a/libproroot.so",
                    "lib/arm64-v8a/libproroot-runtime.so",
                    "lib/arm64-v8a/libproroot-linker.so",
                    "lib/arm64-v8a/libproroot-bridge.so",
                    "lib/arm64-v8a/libproroot-stub-loader.so"
            };

            int found = 0;
            for (String name : names) {
                if (zip.getEntry(name) != null) found++;
            }

            zip.close();
            return found + "/5";
        } catch (Exception e) {
            return "ERROR " + e.getClass().getSimpleName();
        }
    }

    private String runProrootTest() {
        try {
            String proroot = new java.io.File(
                    getApplicationInfo().nativeLibraryDir,
                    "libproroot.so"
            ).getAbsolutePath();

            String rootfs = ProrootRuntime.rootfsDir(this).getAbsolutePath();

            Process process = new ProcessBuilder(
                    proroot,
                    "-r", rootfs,
                    "-0",
                    "--link2symlink",
                    "-w", "/root",
                    "/bin/sh",
                    "-c",
                    "echo PROROOT_OK; id; echo UBUNTU_SOURCES; cat /etc/apt/sources.list.d/ubuntu.sources 2>&1; echo END_UBUNTU_SOURCES; echo HEADLESS_SETUP_START; apt-get update >/tmp/apt-update.log 2>&1; u=$?; echo APT_UPDATE_EXIT=$u; if [ $u -ne 0 ]; then tail -15 /tmp/apt-update.log; exit $u; fi; apt-get install -y ca-certificates curl unzip >/tmp/apt-tools.log 2>&1; t=$?; echo APT_TOOLS_EXIT=$t; if [ $t -ne 0 ]; then tail -20 /tmp/apt-tools.log; exit $t; fi; mkdir -p /opt/chrome; curl -fL --retry 2 'https://storage.googleapis.com/chrome-for-testing-public/155.0.8059.39/linux-arm64/chrome-headless-shell-linux-arm64.zip' -o /tmp/chrome-headless-shell.zip >/tmp/chrome-download.log 2>&1; d=$?; echo CHROME_DOWNLOAD_EXIT=$d; if [ $d -ne 0 ]; then tail -15 /tmp/chrome-download.log; exit $d; fi; unzip -oq /tmp/chrome-headless-shell.zip -d /opt/chrome; z=$?; echo CHROME_UNZIP_EXIT=$z; if [ $z -ne 0 ]; then exit $z; fi; echo CHROME_VERSION; /opt/chrome/chrome-headless-shell-linux-arm64/chrome-headless-shell --version 2>&1; echo CHROME_VERSION_EXIT=$?; echo MISSING_LIBRARIES; ldd /opt/chrome/chrome-headless-shell-linux-arm64/chrome-headless-shell 2>&1 | grep 'not found' || true; echo ZIP_SIZE; du -h /tmp/chrome-headless-shell.zip"
            ).redirectErrorStream(true).start();

            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();

            try (java.io.InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            }

            int exit = process.waitFor();
            return "exit=" + exit + " " +
                    out.toString(java.nio.charset.StandardCharsets.UTF_8.name());

        } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != SCREEN_CAPTURE_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }

        Intent intent = new Intent(this, ScreenCaptureService.class);
        intent.putExtra("resultCode", resultCode);
        intent.putExtra("data", data);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }


    }
    }
