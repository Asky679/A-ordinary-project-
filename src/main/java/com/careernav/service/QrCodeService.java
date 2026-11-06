package com.careernav.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 PDF 页面或图片中识别二维码，并安全提取学信网官方验证链接。 */
@Component
public class QrCodeService {
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile(
            "(?i)(?:vcode|verifycode|verificationcode|code|reportid)[=:：]\\s*([A-Za-z0-9_-]{4,80})");

    private static final Pattern HASH_CODE = Pattern.compile("#([A-Z0-9]{6,30})#");

    public QrResult decode(byte[] bytes, String fileType) {
        if ("PDF".equals(fileType)) return decodePdf(bytes);
        return decodeImage(bytes);
    }

    private QrResult decodePdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pages = Math.min(document.getNumberOfPages(), 5);
            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 220, ImageType.RGB);
                String content = decodeImageText(image);
                if (content != null) return parsed(content, pages);
            }
            return QrResult.notFound(pages);
        } catch (Exception exception) {
            return QrResult.notFound(0);
        }
    }

    private QrResult decodeImage(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return QrResult.notFound(1);
            String content = decodeImageText(image);
            return content == null ? QrResult.notFound(1) : parsed(content, 1);
        } catch (Exception exception) {
            return QrResult.notFound(1);
        }
    }

    private String decodeImageText(BufferedImage image) {
        try {
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return result == null ? null : result.getText();
        } catch (Exception ignored) {
            return null;
        }
    }

    private QrResult parsed(String content, int pages) {
        String raw = clip(content == null ? "" : content.trim(), 800);
        Matcher urlMatcher = URL.matcher(raw);
        String url = urlMatcher.find() ? trimUrl(urlMatcher.group()) : null;
        Matcher codeMatcher = CODE.matcher(raw);
        String code = codeMatcher.find() ? codeMatcher.group(1) : null;
        if (code == null) {
            Matcher hashCode = HASH_CODE.matcher(raw);
            if (hashCode.find()) code = hashCode.group(1);
        }
        if (code == null) {
            Matcher plainCode = Pattern.compile("(?i)(?:在线验证码|验证码)\\s*[:：]?\\s*([A-Z0-9]{6,30})").matcher(raw);
            if (plainCode.find()) code = plainCode.group(1);
        }
        boolean official = isOfficialChsi(url) || (raw.contains("学信网") && raw.contains("在线验证"));
        String status = official ? "OFFICIAL_QR" : "QR_FOUND_UNTRUSTED";
        String label = official && raw.contains("微信小程序") ? "已识别学信网小程序核验二维码"
                : official ? "已识别学信网官方二维码"
                : "已识别二维码，但不是学信网官方域名";
        String officialEntry = isOfficialChsi(url) ? url : "https://www.chsi.com.cn/xlcx/yzzw.jsp";
        return new QrResult(true, status, label, raw, url, code, officialEntry, imageDataUrl(raw), pages);
    }

    private boolean isOfficialChsi(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.equals("chsi.com.cn") || normalized.endsWith(".chsi.com.cn")
                    || normalized.equals("chsi.cn") || normalized.endsWith(".chsi.cn");
        } catch (Exception exception) {
            return false;
        }
    }

    public String imageDataUrl(String content) {
        if (content == null || content.isBlank()) return null;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 240, 240);
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception exception) {
            return null;
        }
    }

    private String trimUrl(String value) {
        return value == null ? null : value.replaceAll("[),，。；;]+$", "");
    }

    private String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record QrResult(boolean detected, String status, String statusLabel, String content,
                           String rawUrl, String verificationCode, String officialVerifyUrl,
                           String imageDataUrl, int pagesScanned) {
        static QrResult notFound(int pages) {
            return new QrResult(false, "NOT_FOUND", "未识别到二维码", null, null, null,
                    "https://www.chsi.com.cn/xlcx/yzzw.jsp", null, pages);
        }
    }
}
