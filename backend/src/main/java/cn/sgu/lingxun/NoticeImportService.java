package cn.sgu.lingxun;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从公开网页抓取通知正文，再把正文交给现有抽取链路。
 *
 * <p>这是“网页正文”入口，不是爬虫：只抓取单页、限制大小和超时，拒绝内网地址，
 * 不执行页面脚本。抓取结果只是候选正文，仍需维护者核对后归档。</p>
 */
@Service
class NoticeImportService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TEXT = 40000;
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*['\"]?([a-zA-Z0-9._-]+)",
            Pattern.CASE_INSENSITIVE);

    record Preview(String sourceUrl, String title, String text, int characters, List<String> warnings) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    Preview fromUrl(String rawUrl) {
        URI uri = validateUrl(rawUrl);
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Lingxun-Notice-Importer/1.0")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
                    .GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("抓取网页时被中断，请稍后重试");
        } catch (Exception unavailable) {
            throw new IllegalArgumentException("无法连接该网页，请检查链接或网络后重试");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            closeQuietly(response.body());
            throw new IllegalArgumentException("网页返回 HTTP " + response.statusCode() + "，无法导入");
        }
        validateHost(response.uri().getHost());
        long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (declared > MAX_BYTES) {
            closeQuietly(response.body());
            throw new IllegalArgumentException("网页正文超过 2 MB，请改为粘贴正文或附件摘录");
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.isBlank() && !isTextContent(contentType)) {
            closeQuietly(response.body());
            throw new IllegalArgumentException("该链接不是 HTML 或纯文本页面，暂不支持二进制文件");
        }
        byte[] bytes;
        try (InputStream body = response.body()) {
            bytes = readLimited(body);
        } catch (IllegalArgumentException tooLarge) {
            throw tooLarge;
        } catch (Exception readError) {
            throw new IllegalArgumentException("读取网页正文失败");
        }

        List<String> warnings = new ArrayList<>();
        if (isTextPlain(contentType)) {
            String text = new String(bytes, charsetOf(contentType, StandardCharsets.UTF_8)).strip();
            return preview(uri, "", text, warnings);
        }
        Charset charset = charsetOf(contentType, StandardCharsets.UTF_8);
        Document document;
        try {
            document = Jsoup.parse(new ByteArrayInputStream(bytes), charset.name(), response.uri().toString());
        } catch (Exception malformed) {
            throw new IllegalArgumentException("网页正文无法解析");
        }
        String title = document.title() == null ? "" : document.title().strip();
        String text = blockText(document);
        warnings.add("只读取网页静态正文，未执行页面脚本；若页面由前端动态渲染，请改为粘贴正文");
        return preview(uri, title, text, warnings);
    }

    Preview fromFile(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("上传文件为空");
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("文件超过 8 MB，请压缩或摘录后粘贴");
        String name = filename == null || filename.isBlank() ? "未命名文件" : filename.replaceAll(".*[/\\\\]", "");
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        List<String> warnings = new ArrayList<>();
        String text;
        String title = "";
        try {
            text = switch (extension) {
                case "txt", "md", "text" -> decodeText(bytes);
                case "html", "htm", "xhtml" -> {
                    Document document = Jsoup.parse(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.name(), "");
                    title = document.title() == null ? "" : document.title().strip();
                    yield blockText(document);
                }
                case "docx" -> docxText(bytes);
                case "pdf" -> {
                    warnings.add("PDF 只提取文字层；扫描件或图片型 PDF 可能没有可用文字");
                    yield pdfText(bytes);
                }
                default -> throw new IllegalArgumentException("仅支持 txt、md、html、docx、pdf 文件");
            };
        } catch (IllegalArgumentException unsupported) {
            throw unsupported;
        } catch (Exception malformed) {
            throw new IllegalArgumentException("文件正文无法解析，请改为粘贴正文或另存为 txt/docx");
        }
        if (text.isBlank()) throw new IllegalArgumentException("文件中没有可读正文");
        return preview(URI.create(""), title.isBlank() ? name.replaceAll("\\.[^.]+$", "") : title, text, warnings);
    }

    private Preview preview(URI uri, String title, String text, List<String> warnings) {
        if (text.isBlank()) throw new IllegalArgumentException("没有从网页提取到可读正文，请改为粘贴正文");
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
            warnings.add("正文超过 40000 字，已截取前 40000 字，请核对");
        }
        String withTitle = title.isBlank() ? text : "标题：" + title + "\n" + text;
        if (withTitle.length() > MAX_TEXT) withTitle = withTitle.substring(0, MAX_TEXT);
        return new Preview(uri.toString(), title, withTitle, withTitle.length(), warnings);
    }

    private static String decodeText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return cleanText(utf8);
        return cleanText(new String(bytes, Charset.forName("GB18030")));
    }

    private static String docxText(byte[] bytes) throws Exception {
        byte[] xml = unzipEntry(bytes, "word/document.xml");
        if (xml == null) throw new IllegalArgumentException("DOCX 缺少 word/document.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        org.w3c.dom.Document xmlDocument = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml));
        String namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        org.w3c.dom.NodeList paragraphs = xmlDocument.getElementsByTagNameNS(namespace, "p");
        if (paragraphs.getLength() == 0) paragraphs = xmlDocument.getElementsByTagName("w:p");
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < paragraphs.getLength(); index++) {
            org.w3c.dom.Element paragraph = (org.w3c.dom.Element) paragraphs.item(index);
            org.w3c.dom.NodeList textNodes = paragraph.getElementsByTagNameNS(namespace, "t");
            if (textNodes.getLength() == 0) textNodes = paragraph.getElementsByTagName("w:t");
            StringBuilder line = new StringBuilder();
            for (int textIndex = 0; textIndex < textNodes.getLength(); textIndex++) {
                line.append(textNodes.item(textIndex).getTextContent());
            }
            String value = line.toString().strip();
            if (!value.isBlank()) lines.add(value);
        }
        return cleanText(String.join("\n", lines));
    }

    private static byte[] unzipEntry(byte[] bytes, String wanted) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals(wanted)) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    if (output.size() + read > MAX_FILE_BYTES) throw new IllegalArgumentException("DOCX 正文解压后过大");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
        return null;
    }

    private static String pdfText(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return cleanText(stripper.getText(document));
        }
    }

    private static String cleanText(String text) {
        return text == null ? "" : text.replace("\u0000", "").replace("\r\n", "\n")
                .replace('\r', '\n').strip();
    }

    static URI validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("请先填写学校通知链接");
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("通知链接格式不正确");
        }
        if (uri.getScheme() == null) throw new IllegalArgumentException("通知链接格式不正确");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("只支持 http 或 https 通知链接");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("通知链接缺少域名");
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("通知链接不能包含用户名或密码");
        validateHost(uri.getHost());
        return uri;
    }

    private static void validateHost(String host) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("通知链接缺少域名");
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal")) {
            throw new IllegalArgumentException("不允许抓取本机或内网地址");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (privateAddress(address)) throw new IllegalArgumentException("不允许抓取本机或内网地址");
            }
        } catch (IllegalArgumentException blocked) {
            throw blocked;
        } catch (Exception unresolved) {
            throw new IllegalArgumentException("通知链接域名无法解析");
        }
    }

    private static boolean privateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) return true; // IPv6 ULA fc00::/7
        return false;
    }

    private static boolean isTextContent(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("text/html") || lower.contains("application/xhtml+xml")
                || lower.contains("text/plain");
    }

    private static boolean isTextPlain(String contentType) {
        return contentType.toLowerCase(Locale.ROOT).contains("text/plain");
    }

    private static Charset charsetOf(String contentType, Charset fallback) {
        Matcher matcher = CHARSET.matcher(contentType == null ? "" : contentType);
        if (!matcher.find()) return fallback;
        try {
            return Charset.forName(matcher.group(1));
        } catch (Exception unsupported) {
            return fallback;
        }
    }

    private static String blockText(Document document) {
        List<String> lines = new ArrayList<>();
        for (Element element : document.select("h1, h2, h3, h4, h5, h6, p, li, tr")) {
            String text = element.text().strip();
            if (!text.isBlank() && !lines.contains(text)) lines.add(text);
        }
        if (lines.isEmpty()) {
            return document.body() == null ? "" : document.body().text().strip();
        }
        return String.join("\n", lines);
    }

    private static byte[] readLimited(InputStream body) throws Exception {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = body.read(buffer)) != -1) {
            if (output.size() + read > MAX_BYTES) {
                throw new IllegalArgumentException("网页正文超过 2 MB，请改为粘贴正文或附件摘录");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void closeQuietly(InputStream stream) {
        try {
            if (stream != null) stream.close();
        } catch (Exception ignored) {
            // 关闭失败不影响用户看到的导入错误。
        }
    }
}
