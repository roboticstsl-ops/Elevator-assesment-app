package com.tsl.rfsurvey;

import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Renders an HTML string in an offscreen WebView and hands it to Android's
 * print framework — the system "Save as PDF" / printer dialog.
 */
@CapacitorPlugin(name = "NativePrint")
public class NativePrintPlugin extends Plugin {

    private WebView printView; // kept alive until the print job is created

    @PluginMethod
    public void printHtml(final PluginCall call) {
        final String html = call.getString("html", "");
        final String jobName = call.getString("jobName", "Report");

        getActivity().runOnUiThread(() -> {
            try {
                WebView wv = new WebView(getContext());
                wv.getSettings().setJavaScriptEnabled(false);
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        try {
                            PrintManager pm = (PrintManager)
                                getContext().getSystemService(Context.PRINT_SERVICE);
                            PrintDocumentAdapter adapter =
                                view.createPrintDocumentAdapter(jobName);
                            pm.print(jobName, adapter,
                                new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .build());
                            call.resolve();
                        } catch (Exception e) {
                            call.reject("print failed: " + e.getMessage());
                        }
                    }
                });
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                printView = wv;
            } catch (Exception e) {
                call.reject("print setup failed: " + e.getMessage());
            }
        });
    }
}
