package cn.sgu.lingxun;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoticeImportServiceTest {
    @Test
    void acceptsPublicHttpAndHttpsUrls() {
        assertThat(NoticeImportService.validateUrl("https://example.edu/notice/1").getHost())
                .isEqualTo("example.edu");
        assertThat(NoticeImportService.validateUrl("http://example.edu/notice/1").getScheme())
                .isEqualTo("http");
    }

    @Test
    void rejectsNonHttpSchemesAndEmbeddedCredentials() {
        assertThatThrownBy(() -> NoticeImportService.validateUrl("file:///C:/secret.txt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http");
        assertThatThrownBy(() -> NoticeImportService.validateUrl("https://user:pass@example.edu/a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("用户名或密码");
        assertThatThrownBy(() -> NoticeImportService.validateUrl("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("格式");
    }

    @Test
    void rejectsLoopbackAndPrivateHostsBeforeAnyNetworkCall() {
        assertThatThrownBy(() -> NoticeImportService.validateUrl("http://127.0.0.1:8099/admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("内网");
        assertThatThrownBy(() -> NoticeImportService.validateUrl("http://localhost:8099/"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("内网");
        assertThatThrownBy(() -> NoticeImportService.validateUrl("http://192.168.1.10/notice"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("内网");
        assertThatThrownBy(() -> NoticeImportService.validateUrl("http://10.0.0.8/notice"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("内网");
    }

    @Test
    void readsTextAndHtmlFilesWithoutCallingTheNetwork() {
        NoticeImportService service = new NoticeImportService();
        NoticeImportService.Preview txt = service.fromFile("notice.txt",
                "适用对象：全日制本科生。\n报名时间：2026年11月20日17:00。".getBytes(StandardCharsets.UTF_8));
        assertThat(txt.text()).contains("适用对象", "报名时间");

        NoticeImportService.Preview html = service.fromFile("notice.html",
                "<html><head><title>示例通知</title></head><body><p>材料要求：报名表。</p><li>办理入口：教务系统。</li></body></html>"
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(html.title()).isEqualTo("示例通知");
        assertThat(html.text()).contains("材料要求：报名表。", "办理入口：教务系统。");
    }

    @Test
    void extractsDocxParagraphsWithBuiltInZipAndXmlParsing() throws Exception {
        NoticeImportService service = new NoticeImportService();
        NoticeImportService.Preview preview = service.fromFile("通知.docx", docx(
                "适用对象：全日制本科生。", "报名时间：2026年12月1日09:00至2026年12月5日17:00。"));

        assertThat(preview.text()).contains("适用对象：全日制本科生。", "报名时间：2026年12月1日09:00至2026年12月5日17:00。");
        assertThat(preview.characters()).isGreaterThan(20);
    }

    @Test
    void rejectsUnsupportedBinaryFormatsInsteadOfGuessing() {
        assertThatThrownBy(() -> new NoticeImportService().fromFile("notice.doc", new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("txt");
    }

    @Test
    void extractsTextLayerFromPdfAndWarnsAboutScannedPdfs() throws Exception {
        byte[] pdf = pdf("Notice deadline 2026-11-20 17:00");
        NoticeImportService.Preview preview = new NoticeImportService().fromFile("notice.pdf", pdf);
        assertThat(preview.text()).contains("Notice deadline 2026-11-20 17:00");
        assertThat(preview.warnings()).anyMatch(warning -> warning.contains("扫描件"));
    }

    private static byte[] pdf(String line) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(line);
                content.endText();
            }
            document.save(output);
        }
        return output.toByteArray();
    }

    private static byte[] docx(String... paragraphs) throws Exception {
        String body = String.join("", java.util.Arrays.stream(paragraphs).map(text ->
                "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>").toList());
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
