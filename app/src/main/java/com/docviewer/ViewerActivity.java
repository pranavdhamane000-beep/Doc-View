package com.docviewer;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ViewerActivity extends AppCompatActivity {

    private enum FileType { PDF, DOCX, PPTX, PPT, UNKNOWN }

    private Uri fileUri;
    private FileType fileType = FileType.UNKNOWN;
    private File localFile;

    private TextView tvFileName, tvPageInfo, tvSlideNumber;
    private ImageView pdfImageView;
    private WebView webView;
    private ScrollView pdfScrollView;
    private LinearLayout slideNavigation;
    private ProgressBar loadingBar;
    private ImageButton btnPrev, btnNext, btnBack;
    private SwipeRefreshLayout swipeRefresh;

    private PdfRenderer pdfRenderer;
    private int currentPage = 0;
    private int totalPages = 0;

    private List<String> slideHtmlCache = new ArrayList<>();
    private int currentSlide = 0;
    private int totalSlides = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        tvFileName = findViewById(R.id.tvFileName);
        tvPageInfo = findViewById(R.id.tvPageInfo);
        tvSlideNumber = findViewById(R.id.tvSlideNumber);
        pdfImageView = findViewById(R.id.pdfImageView);
        webView = findViewById(R.id.webView);
        pdfScrollView = findViewById(R.id.pdfScrollView);
        slideNavigation = findViewById(R.id.slideNavigation);
        loadingBar = findViewById(R.id.loadingBar);
        btnPrev = findViewById(R.id.btnPrevSlide);
        btnNext = findViewById(R.id.btnNextSlide);
        btnBack = findViewById(R.id.btnBack);
        swipeRefresh = findViewById(R.id.swipeRefresh);

        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setJavaScriptEnabled(true);

        btnBack.setOnClickListener(v -> finish());
        swipeRefresh.setEnabled(false);

        fileUri = getIntent().getData();
        if (fileUri == null) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String name = fileUri.getLastPathSegment();
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".pdf")) fileType = FileType.PDF;
            else if (lower.endsWith(".docx") || lower.endsWith(".doc")) fileType = FileType.DOCX;
            else if (lower.endsWith(".pptx")) fileType = FileType.PPTX;
            else if (lower.endsWith(".ppt")) fileType = FileType.PPT;
        }

        tvFileName.setText(name != null ? name : "Document");

        loadingBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                localFile = copyToCache(fileUri);
                runOnUiThread(() -> openDocument());
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private File copyToCache(Uri uri) throws IOException {
        String seg = uri.getLastPathSegment();
        String safeName = seg != null ? seg.replaceAll("[^a-zA-Z0-9._-]", "_") : "doc";
        File cacheFile = new File(getCacheDir(), "doc_" + System.currentTimeMillis() + "_" + safeName);
        try (InputStream is = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(cacheFile)) {
            if (is == null) throw new IOException("Cannot open file");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        }
        return cacheFile;
    }

    private void openDocument() {
        try {
            switch (fileType) {
                case PDF:
                    openPDF();
                    break;
                case DOCX:
                    openDOCX();
                    break;
                case PPTX:
                    openPPTX();
                    break;
                case PPT:
                    openLegacyPPT();
                    break;
                default:
                    Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show();
                    finish();
            }
        } catch (Exception e) {
            loadingBar.setVisibility(View.GONE);
            Toast.makeText(this, "Error opening file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== PDF VIEWER ====================
    private void openPDF() throws IOException {
        pdfScrollView.setVisibility(View.VISIBLE);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY);
        pdfRenderer = new PdfRenderer(pfd);
        totalPages = pdfRenderer.getPageCount();
        tvPageInfo.setVisibility(View.VISIBLE);
        tvPageInfo.setText(getString(R.string.page_x_of_y, 1, totalPages));
        loadingBar.setVisibility(View.GONE);
        renderPdfPage(0);

        swipeRefresh.setEnabled(true);
        swipeRefresh.setOnRefreshListener(() -> {
            swipeRefresh.setRefreshing(false);
            if (currentPage < totalPages - 1) renderPdfPage(currentPage + 1);
        });

        pdfScrollView.setOnTouchListener(new SwipeListener(this) {
            @Override
            public void onSwipeDown() {
                if (currentPage > 0) renderPdfPage(currentPage - 1);
            }
        });
    }

    private void renderPdfPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= totalPages) return;
        currentPage = pageIndex;
        PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
        int width = page.getWidth();
        int height = page.getHeight();
        float scale = (float) getResources().getDisplayMetrics().widthPixels / width;
        Bitmap bitmap = Bitmap.createBitmap((int)(width * scale), (int)(height * scale), Bitmap.Config.ARGB_8888);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();
        pdfImageView.setImageBitmap(bitmap);
        pdfScrollView.scrollTo(0, 0);
        tvPageInfo.setText(getString(R.string.page_x_of_y, pageIndex + 1, totalPages));
    }

    // ==================== DOCX VIEWER ====================
    private void openDOCX() {
        webView.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                StringBuilder html = new StringBuilder();
                html.append("<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
                html.append("<style>");
                html.append("body{font-family:'Segoe UI',Arial,sans-serif;font-size:16px;color:#333;line-height:1.6;padding:16px;margin:0;background:#fff;}");
                html.append("p{margin:8px 0;}");
                html.append("h1,h2,h3{color:#1A73E8;}");
                html.append("h1{font-size:24px;}h2{font-size:20px;}h3{font-size:18px;}");
                html.append("b,strong{color:#222;}");
                html.append("em,i{color:#555;}");
                html.append("table{border-collapse:collapse;width:100%;margin:12px 0;}");
                html.append("td,th{border:1px solid #ddd;padding:8px;text-align:left;}");
                html.append("th{background:#1A73E8;color:white;}");
                html.append("img{max-width:100%;height:auto;margin:8px 0;}");
                html.append("</style></head><body>");

                FileInputStream fis = new FileInputStream(localFile);
                XWPFDocument doc = new XWPFDocument(fis);

                List<XWPFPictureData> pictures = doc.getAllPictures();
                String imgDir = getCacheDir().getAbsolutePath() + "/doc_images/";
                new File(imgDir).mkdirs();
                for (int i = 0; i < pictures.size(); i++) {
                    XWPFPictureData pic = pictures.get(i);
                    File imgFile = new File(imgDir, "img_" + i + "." + getExtension(pic.getPictureType()));
                    try (FileOutputStream fos = new FileOutputStream(imgFile)) {
                        fos.write(pic.getData());
                    }
                }

                for (IBodyElement element : doc.getBodyElements()) {
                    if (element instanceof XWPFParagraph) {
                        XWPFParagraph para = (XWPFParagraph) element;
                        String pText = para.getText();
                        if (pText == null || pText.trim().isEmpty()) continue;

                        String style = para.getStyle();
                        boolean isHeading = style != null && style.toLowerCase().contains("heading");
                        boolean isBold = false;
                        for (XWPFRun run : para.getRuns()) {
                            if (run.getBold()) { isBold = true; break; }
                        }

                        if (isHeading) {
                            String level = style.replaceAll("[^0-9]", "");
                            int hLevel = level.isEmpty() ? 1 : Math.min(Integer.parseInt(level), 6);
                            html.append("<h").append(hLevel).append(">")
                               .append(escapeHtml(pText)).append("</h").append(hLevel).append(">");
                        } else if (isBold && pText.length() < 80) {
                            html.append("<p><b>").append(escapeHtml(pText)).append("</b></p>");
                        } else {
                            html.append("<p>");
                            for (XWPFRun run : para.getRuns()) {
                                String text = run.getText(0);
                                if (text == null) continue;
                                if (run.getBold()) html.append("<b>");
                                if (run.getItalic()) html.append("<i>");
                                if (run.getUnderline() != null) html.append("<u>");
                                html.append(escapeHtml(text));
                                if (run.getUnderline() != null) html.append("</u>");
                                if (run.getItalic()) html.append("</i>");
                                if (run.getBold()) html.append("</b>");
                            }
                            html.append("</p>");
                        }

                        // Check for pictures in paragraph runs
                        for (XWPFRun run : para.getRuns()) {
                            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                                try {
                                    String blipRelId = pic.getCTPicture().getBlipFill().getBlip().getEmbed();
                                    for (int pi = 0; pi < pictures.size(); pi++) {
                                        if (pictures.get(pi).getRelationId(doc.getRelationById(blipRelId)).contains(blipRelId)) {
                                            html.append("<img src=\"file:///" + imgDir + "img_" + pi + "." + getExtension(pictures.get(pi).getPictureType()) + "\">");
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }

                doc.close();
                fis.close();
                html.append("</body></html>");

                String finalHtml = html.toString();
                String baseUrl = "file:///" + getCacheDir().getAbsolutePath() + "/";
                runOnUiThread(() -> {
                    webView.loadDataWithBaseURL(baseUrl, finalHtml, "text/html", "UTF-8", null);
                    loadingBar.setVisibility(View.GONE);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingBar.setVisibility(View.GONE);
                    Toast.makeText(this, "DOCX Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ==================== PPTX VIEWER ====================
    private void openPPTX() {
        slideNavigation.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);

        btnPrev.setOnClickListener(v -> {
            if (currentSlide > 0) { currentSlide--; showSlide(currentSlide); }
        });
        btnNext.setOnClickListener(v -> {
            if (currentSlide < totalSlides - 1) { currentSlide++; showSlide(currentSlide); }
        });

        new Thread(() -> {
            try {
                String imgDir = getCacheDir().getAbsolutePath() + "/pptx_images/";
                new File(imgDir).mkdirs();

                FileInputStream fis = new FileInputStream(localFile);
                XMLSlideShow pptx = new XMLSlideShow(fis);
                totalSlides = pptx.getSlides().size();

                double slideWidth = pptx.getSlideSize().getWidth().doubleValue();
                double slideHeight = pptx.getSlideSize().getHeight().doubleValue();
                double scaleX = 100.0 / slideWidth;
                double scaleY = 100.0 / slideHeight;

                // Extract images from all slides
                List<XSLFPictureData> allPics = pptx.getAllPictures();
                Map<XSLFPictureData, String> picFileNames = new HashMap<>();
                for (XSLFPictureData pd : allPics) {
                    String fname = pd.getFileName().replaceAll("[^a-zA-Z0-9._-]", "_");
                    String safeName = "pic_" + System.currentTimeMillis() + "_" + fname;
                    picFileNames.put(pd, safeName);
                    File imgFile = new File(imgDir, safeName);
                    try (FileOutputStream fos = new FileOutputStream(imgFile)) {
                        fos.write(pd.getData());
                    }
                }

                // Generate HTML for each slide
                for (int i = 0; i < totalSlides; i++) {
                    XSLFSlide slide = pptx.getSlides().get(i);
                    slideHtmlCache.add(generateSlideHtml(slide, i, imgDir, scaleX, scaleY, picFileNames));
                }

                pptx.close();
                fis.close();

                runOnUiThread(() -> {
                    loadingBar.setVisibility(View.GONE);
                    tvSlideNumber.setText(getString(R.string.slide_x_of_y, 1, totalSlides));
                    if (totalSlides > 0) showSlide(0);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingBar.setVisibility(View.GONE);
                    Toast.makeText(this, "PPTX Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String generateSlideHtml(XSLFSlide slide, int slideIndex, String imgDir,
                                      double scaleX, double scaleY,
                                      Map<XSLFPictureData, String> picFileNames) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">");
        html.append("<style>");
        html.append("*{box-sizing:border-box;margin:0;padding:0;}");
        html.append("body{background:#222;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;overflow:hidden;}");
        html.append(".slide-wrap{width:100vw;height:100vh;display:flex;justify-content:center;align-items:center;}");
        html.append(".slide{width:100%;height:100%;position:relative;overflow:hidden;background:#fff;}");
        html.append(".shape{position:absolute;overflow:hidden;}");
        html.append(".text-box{padding:2px 4px;white-space:pre-wrap;word-wrap:break-word;}");
        html.append("</style></head><body><div class=\"slide-wrap\"><div class=\"slide\">");

        try {
            org.apache.poi.sl.draw.DrawingPaint paint = slide.getBackground().getFill();
            if (paint != null) {
                try {
                    java.awt.Color bgC = slide.getBackground().getFillColor();
                    if (bgC != null) {
                        html.append("background:rgb(").append(bgC.getRed()).append(",")
                           .append(bgC.getGreen()).append(",").append(bgC.getBlue()).append(");");
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        for (XSLFShape shape : slide.getShapes()) {
            try {
                java.awt.geom.Rectangle2D anchor = shape.getAnchor();
                if (anchor == null) continue;
                double x = anchor.getX() * scaleX;
                double y = anchor.getY() * scaleY;
                double w = Math.max(anchor.getWidth() * scaleX, 1);
                double h = Math.max(anchor.getHeight() * scaleY, 1);

                html.append("<div class=\"shape\" style=\"left:").append(x).append("%;top:")
                   .append(y).append("%;width:").append(w).append("%;height:")
                   .append(h).append("%;\">");

                if (shape instanceof XSLFTextShape) {
                    XSLFTextShape textShape = (XSLFTextShape) shape;

                    // Background color
                    try {
                        java.awt.Color fillC = textShape.getFillColor();
                        if (fillC != null) {
                            html.append("background:rgb(").append(fillC.getRed()).append(",")
                               .append(fillC.getGreen()).append(",").append(fillC.getBlue()).append(");");
                        }
                    } catch (Exception ignored) {}

                    html.append("<div class=\"text-box\" style=\"");

                    for (XSLFTextParagraph para : textShape) {
                        for (XSLFTextRun run : para) {
                            try {
                                java.awt.Color fontColor = run.getFontColor();
                                if (fontColor != null) {
                                    html.append("color:rgb(").append(fontColor.getRed()).append(",")
                                       .append(fontColor.getGreen()).append(",").append(fontColor.getBlue()).append(");");
                                }
                            } catch (Exception ignored) {}

                            if (run.getFontSize() > 0) {
                                double fontSize = run.getFontSize() * scaleX * 8;
                                fontSize = Math.max(fontSize, 8);
                                fontSize = Math.min(fontSize, 60);
                                html.append("font-size:").append(String.format("%.1f", fontSize)).append("px;");
                            }
                            if (run.isBold()) html.append("font-weight:bold;");
                            if (run.isItalic()) html.append("font-style:italic;");
                        }
                    }

                    // Alignment
                    if (textShape.getTextParagraphs().size() > 0) {
                        XSLFTextParagraph firstPara = textShape.getTextParagraphs().get(0);
                        switch (firstPara.getTextAlign()) {
                            case CENTER: html.append("text-align:center;"); break;
                            case RIGHT: html.append("text-align:right;"); break;
                        }
                    }

                    html.append("\">");

                    for (XSLFTextParagraph para : textShape) {
                        String text = para.getText();
                        if (text != null && !text.trim().isEmpty()) {
                            html.append("<p style=\"margin:1px 0;\">").append(escapeHtml(text)).append("</p>");
                        }
                    }
                    html.append("</div>");

                } else if (shape instanceof XSLFPictureShape) {
                    XSLFPictureShape picShape = (XSLFPictureShape) shape;
                    XSLFPictureData picData = picShape.getPictureData();
                    String fileName = picFileNames.get(picData);
                    if (fileName != null) {
                        html.append("<img src=\"file:///" + imgDir + fileName + "\" style=\"width:100%;height:100%;object-fit:contain;\">");
                    }
                }

                html.append("</div>");
            } catch (Exception e) {
                // Skip problematic shapes
            }
        }

        html.append("</div></div></body></html>");
        return html.toString();
    }

    // ==================== LEGACY PPT (.ppt) VIEWER ====================
    private void openLegacyPPT() {
        slideNavigation.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);

        btnPrev.setOnClickListener(v -> {
            if (currentSlide > 0) { currentSlide--; showSlide(currentSlide); }
        });
        btnNext.setOnClickListener(v -> {
            if (currentSlide < totalSlides - 1) { currentSlide++; showSlide(currentSlide); }
        });

        new Thread(() -> {
            try {
                FileInputStream fis = new FileInputStream(localFile);
                HSLFSlideShow ppt = new HSLFSlideShow(fis);
                totalSlides = ppt.getSlides().size();

                java.awt.Dimension pageSize = ppt.getPageSize();
                double scaleX = 100.0 / pageSize.width;
                double scaleY = 100.0 / pageSize.height;

                for (int i = 0; i < totalSlides; i++) {
                    HSLFSlide slide = ppt.getSlides().get(i);
                    StringBuilder sHtml = new StringBuilder();
                    sHtml.append("<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
                    sHtml.append("<style>body{margin:0;background:#222;display:flex;justify-content:center;align-items:center;min-height:100vh;}");
                    sHtml.append(".slide{width:100vw;height:100vh;position:relative;background:#fff;overflow:hidden;}");
                    sHtml.append(".shape{position:absolute;overflow:hidden;padding:2px 4px;white-space:pre-wrap;}</style>");
                    sHtml.append("</head><body><div class=\"slide\">");

                    for (HSLFShape shape : slide.getShapes()) {
                        try {
                            java.awt.Rectangle anchor = shape.getAnchor();
                            double x = anchor.x * scaleX;
                            double y = anchor.y * scaleY;
                            double w = Math.max(anchor.width * scaleX, 1);
                            double h = Math.max(anchor.height * scaleY, 1);

                            sHtml.append("<div class=\"shape\" style=\"left:").append(x).append("%;top:")
                               .append(y).append("%;width:").append(w).append("%;height:")
                               .append(h).append("%;\">");

                            if (shape instanceof HSLFTextShape) {
                                HSLFTextShape textShape = (HSLFTextShape) shape;
                                try {
                                    java.awt.Color fillC = textShape.getFillColor();
                                    if (fillC != null) {
                                        sHtml.append("background:rgb(").append(fillC.getRed()).append(",")
                                           .append(fillC.getGreen()).append(",").append(fillC.getBlue()).append(");");
                                    }
                                } catch (Exception ignored) {}

                                for (HSLFTextParagraph para : textShape) {
                                    StringBuilder paraText = new StringBuilder();
                                    for (HSLFTextRun run : para) {
                                        try {
                                            String txt = run.getRawText();
                                            if (txt != null) paraText.append(escapeHtml(txt));
                                        } catch (Exception ignored) {}
                                    }
                                    String t = paraText.toString().trim();
                                    if (!t.isEmpty()) {
                                        sHtml.append("<p style=\"margin:1px 0;\">").append(t).append("</p>");
                                    }
                                }
                            }

                            sHtml.append("</div>");
                        } catch (Exception ignored) {}
                    }

                    sHtml.append("</div></body></html>");
                    slideHtmlCache.add(sHtml.toString());
                }

                ppt.close();
                fis.close();

                runOnUiThread(() -> {
                    loadingBar.setVisibility(View.GONE);
                    tvSlideNumber.setText(getString(R.string.slide_x_of_y, 1, totalSlides));
                    if (totalSlides > 0) showSlide(0);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingBar.setVisibility(View.GONE);
                    Toast.makeText(this, "PPT Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showSlide(int index) {
        if (index >= 0 && index < slideHtmlCache.size()) {
            String baseUrl = "file:///" + getCacheDir().getAbsolutePath() + "/";
            webView.loadDataWithBaseURL(baseUrl, slideHtmlCache.get(index), "text/html", "UTF-8", null);
            tvSlideNumber.setText(getString(R.string.slide_x_of_y, index + 1, totalSlides));
            currentSlide = index;
        }
    }

    private String getExtension(int pictureType) {
        switch (pictureType) {
            case XWPFPictureData.PICTURE_TYPE_PNG: return "png";
            case XWPFPictureData.PICTURE_TYPE_JPEG: return "jpg";
            case XWPFPictureData.PICTURE_TYPE_GIF: return "gif";
            case XWPFPictureData.PICTURE_TYPE_BMP: return "bmp";
            default: return "png";
        }
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
    }

    private abstract static class SwipeListener implements View.OnTouchListener {
        private static final int SWIPE_THRESHOLD = 150;
        private float startY;

        SwipeListener(Context ctx) {}

        @Override
        public boolean onTouch(View v, android.view.MotionEvent event) {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startY = event.getY();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    float diffY = event.getY() - startY;
                    if (diffY > SWIPE_THRESHOLD) onSwipeDown();
                    break;
            }
            return false;
        }

        public abstract void onSwipeDown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pdfRenderer != null) {
            try { pdfRenderer.close(); } catch (Exception ignored) {}
        }
        if (localFile != null && localFile.exists()) localFile.delete();
    }
}
