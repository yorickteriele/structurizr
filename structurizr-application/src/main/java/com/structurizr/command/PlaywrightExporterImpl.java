package com.structurizr.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;
import com.structurizr.util.StringUtils;
import com.structurizr.view.ColorScheme;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PlaywrightExporterImpl implements PlaywrightExporter {

    private static final Log log = LogFactory.getLog(PlaywrightExporterImpl.class);

    @Override
    public void run(String url, String format, ColorScheme colorScheme, boolean animation, File outputDir) {
        List<Diagram> diagrams = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setBypassCSP(true));

            log.info("Visiting " + url);
            Page page = context.newPage();
            page.navigate(url);

            page.addScriptTag(new Page.AddScriptTagOptions().setContent("""
                var finished = false;
                
                function exportFinished() {
                    finished = true;
                }
                """));

            page.exposeBinding("exportDiagram", (source, args) -> {
                // (view, diagram, diagramKey, download)
                HashMap<String,String> diagramMap = (HashMap<String,String>)args[1];
                DiagramFile diagram = new DiagramFile(diagramMap.get("filename"), diagramMap.get("content"));
                DiagramFile key = null;

                if (args[2] != null) {
                    HashMap<String,String> diagramKeyMap = (HashMap<String,String>)args[2];
                    key = new DiagramFile(diagramKeyMap.get("filename"), diagramKeyMap.get("content"));
                }

                diagrams.add(new Diagram(diagram, key));

                return true;
            });

            page.waitForFunction("structurizr.scripting && structurizr.scripting.isDiagramRendered() === true");

            page.evaluate(String.format("""
                structurizr.scripting.setDarkMode(%s);
                const views = structurizr.scripting.getViews().map(function(view) { return view.key; });
                structurizr.scripting.exportViews(views, { format: '%s', prefix: '', publish: true, metadata: true, animation: %s }, window.exportDiagram, window.exportFinished);
                """, colorScheme == ColorScheme.Dark, format, animation));

            page.waitForFunction("finished === true");

            browser.close();
        }

        for (Diagram diagram : diagrams) {
            save(diagram.getDiagram(), outputDir);

            if (diagram.hasKey()) {
                save(diagram.getKey(), outputDir);
            }
        }
    }

    // A4 landscape, in CSS pixels (96dpi)
    private static final double PDF_PAGE_WIDTH = 1122;
    private static final double PDF_PAGE_HEIGHT = 793;
    private static final double PDF_PAGE_PADDING = 24;
    private static final double PDF_HEADER_HEIGHT = 56;
    private static final double PDF_FOOTER_HEIGHT = 28;
    private static final Pattern SVG_DIMENSIONS = Pattern.compile("<svg[^>]*?width=\"([\\d.]+)\"[^>]*?height=\"([\\d.]+)\"");

    @Override
    public void runPdf(String url, ColorScheme colorScheme, File outputFile) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setBypassCSP(true));

            log.info("Visiting " + url);
            Page page = context.newPage();
            page.navigate(url);
            page.waitForFunction("structurizr.scripting && structurizr.scripting.isDiagramRendered() === true");

            // export every view as SVG, along with the elements that can be "zoomed into"
            String json = (String)page.evaluate(String.format("""
                new Promise(function(resolve) {
                    structurizr.scripting.setDarkMode(%s);
                    const views = structurizr.scripting.getViews();
                    const results = [];
                    structurizr.scripting.exportViews(
                        views.map(function(view) { return view.key; }),
                        { format: 'svg', prefix: '', publish: true, metadata: true, animation: false },
                        function(view, diagram, diagramKey) {
                            const metadata = views.find(function(v) { return v.key === view.key; });
                            results.push({
                                key: view.key,
                                name: metadata ? metadata.name : view.key,
                                description: metadata ? metadata.description : '',
                                type: view.type,
                                content: diagram.content,
                                links: structurizr.diagram.getNavigationLinks ? structurizr.diagram.getNavigationLinks() : []
                            });
                        },
                        function() { resolve(JSON.stringify(results)); }
                    );
                })
                """, colorScheme == ColorScheme.Dark));

            browser.close();

            byte[] pdf = renderPdf(json, colorScheme);
            if (pdf != null) {
                log.info("Writing " + outputFile.getCanonicalPath());
                Files.write(outputFile.toPath(), pdf);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] renderPdf(String viewsAsJson, ColorScheme colorScheme) {
        List<PdfView> views;
        try {
            views = parseViews(viewsAsJson);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (views.isEmpty()) {
            log.error("The workspace contains no views");
            return null;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setContent(buildPdfHtml(views, colorScheme));

            byte[] pdf = page.pdf(new Page.PdfOptions()
                    .setPrintBackground(true)
                    .setPreferCSSPageSize(true)
                    .setMargin(new Margin().setTop("0").setBottom("0").setLeft("0").setRight("0"))
                    .setOutline(true)
                    .setTagged(true));

            browser.close();

            return pdf;
        }
    }

    private List<PdfView> parseViews(String json) throws IOException {
        List<PdfView> views = new ArrayList<>();
        JsonNode array = new ObjectMapper().readTree(json);

        int index = 1;
        for (JsonNode node : array) {
            PdfView view = new PdfView();
            view.anchor = "view-" + index++;
            view.key = node.path("key").asText();
            view.name = node.path("name").asText(view.key);
            view.description = node.path("description").asText("");
            view.content = node.path("content").asText("");
            if (!view.content.startsWith("data:image/")) {
                continue;
            }

            if (view.content.startsWith("data:image/svg+xml;base64,")) {
                String svg = new String(Base64.getDecoder().decode(view.content.split(",")[1]), StandardCharsets.UTF_8);
                Matcher matcher = SVG_DIMENSIONS.matcher(svg);
                if (matcher.find()) {
                    view.width = Double.parseDouble(matcher.group(1));
                    view.height = Double.parseDouble(matcher.group(2));
                }
            }

            for (JsonNode linkNode : node.path("links")) {
                PdfLink link = new PdfLink();
                link.name = linkNode.path("name").asText();
                link.x = linkNode.path("x").asDouble();
                link.y = linkNode.path("y").asDouble();
                link.width = linkNode.path("width").asDouble();
                link.height = linkNode.path("height").asDouble();
                for (JsonNode viewKey : linkNode.path("views")) {
                    link.views.add(viewKey.asText());
                }
                view.links.add(link);
            }

            views.add(view);
        }

        return views;
    }

    private String buildPdfHtml(List<PdfView> views, ColorScheme colorScheme) {
        Map<String,PdfView> viewsByKey = new LinkedHashMap<>();
        views.forEach(v -> viewsByKey.put(v.key, v));

        // work out the "parent" views (i.e. the views that zoom into each view), for the "back" links
        Map<String,Set<PdfView>> parents = new HashMap<>();
        for (PdfView view : views) {
            for (PdfLink link : view.links) {
                for (String target : link.views) {
                    parents.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(view);
                }
            }
        }

        boolean dark = colorScheme == ColorScheme.Dark;
        StringBuilder buf = new StringBuilder();
        buf.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Structurizr</title><style>");
        buf.append(String.format("""
                @page { size: %spx %spx; margin: 0; }
                * { box-sizing: border-box; }
                html, body { margin: 0; padding: 0; }
                body { font-family: "Open Sans", Arial, Helvetica, sans-serif; background: %s; color: %s; }
                a { color: %s; text-decoration: none; }
                .page { width: %spx; height: %spx; padding: %spx; position: relative; overflow: hidden; page-break-after: always; break-after: page; }
                .page:last-child { page-break-after: auto; break-after: auto; }
                .header { height: %spx; display: flex; flex-direction: column; justify-content: center; border-bottom: 1px solid %s; }
                .nav { font-size: 11px; opacity: 0.85; }
                .nav a { margin-right: 12px; }
                h1 { font-size: 20px; margin: 0; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .diagram-area { display: flex; align-items: center; justify-content: center; }
                .diagram { position: relative; }
                .diagram img { width: 100%%; height: 100%%; display: block; }
                .diagram a.zoom { position: absolute; display: block; }
                .footer { position: absolute; left: %spx; right: %spx; bottom: %spx; height: %spx; font-size: 10px; opacity: 0.85; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; padding-top: 8px; }
                .contents h1 { font-size: 28px; margin-bottom: 8px; }
                .contents ol { columns: 2; column-gap: 40px; font-size: 13px; padding-left: 20px; }
                .contents li { margin-bottom: 6px; break-inside: avoid; }
                .contents .description { font-size: 11px; opacity: 0.75; }
                """,
                (int)PDF_PAGE_WIDTH, (int)PDF_PAGE_HEIGHT,
                dark ? "#111111" : "#ffffff", dark ? "#eeeeee" : "#111111", dark ? "#8ab4f8" : "#1168bd",
                (int)PDF_PAGE_WIDTH, (int)PDF_PAGE_HEIGHT, (int)PDF_PAGE_PADDING,
                (int)PDF_HEADER_HEIGHT, dark ? "#444444" : "#dddddd",
                (int)PDF_PAGE_PADDING, (int)PDF_PAGE_PADDING, (int)PDF_PAGE_PADDING, (int)PDF_FOOTER_HEIGHT));
        buf.append("</style></head><body>");

        // contents page
        buf.append("<section class=\"page contents\" id=\"contents\"><h1>Contents</h1><ol>");
        for (PdfView view : views) {
            buf.append("<li><a href=\"#").append(view.anchor).append("\">").append(escape(view.name)).append("</a>");
            if (!view.description.isEmpty()) {
                buf.append("<div class=\"description\">").append(escape(view.description)).append("</div>");
            }
            buf.append("</li>");
        }
        buf.append("</ol></section>");

        double areaWidth = PDF_PAGE_WIDTH - (2 * PDF_PAGE_PADDING);
        double areaHeight = PDF_PAGE_HEIGHT - (2 * PDF_PAGE_PADDING) - PDF_HEADER_HEIGHT - PDF_FOOTER_HEIGHT;

        for (PdfView view : views) {
            buf.append("<section class=\"page\" id=\"").append(view.anchor).append("\">");

            // header: title + navigation back up the hierarchy
            buf.append("<div class=\"header\"><div class=\"nav\"><a href=\"#contents\">&#8962; Contents</a>");
            for (PdfView parent : parents.getOrDefault(view.key, Collections.emptySet())) {
                buf.append("<a href=\"#").append(parent.anchor).append("\">&#8593; ").append(escape(parent.name)).append("</a>");
            }
            buf.append("</div><h1>").append(escape(view.name)).append("</h1></div>");

            // diagram, scaled to fit the page, with clickable areas over elements that can be zoomed into
            buf.append("<div class=\"diagram-area\" style=\"height: ").append(areaHeight).append("px\">");
            if (view.width > 0 && view.height > 0) {
                double scale = Math.min(areaWidth / view.width, areaHeight / view.height);
                buf.append(String.format(Locale.ROOT, "<div class=\"diagram\" style=\"width: %.2fpx; height: %.2fpx\">", view.width * scale, view.height * scale));
                buf.append("<img src=\"").append(escape(view.content)).append("\" alt=\"").append(escape(view.name)).append("\">");

                for (PdfLink link : view.links) {
                    PdfView target = link.views.stream().map(viewsByKey::get).filter(Objects::nonNull).findFirst().orElse(null);
                    if (target != null) {
                        buf.append(String.format(Locale.ROOT,
                                "<a class=\"zoom\" href=\"#%s\" title=\"%s\" style=\"left: %.2fpx; top: %.2fpx; width: %.2fpx; height: %.2fpx\"></a>",
                                target.anchor, escape(link.name + " - " + target.name),
                                link.x * scale, link.y * scale, link.width * scale, link.height * scale));
                    }
                }
                buf.append("</div>");
            } else {
                buf.append(String.format(Locale.ROOT, "<img src=\"%s\" style=\"max-width: %.2fpx; max-height: %.2fpx\" alt=\"%s\">", escape(view.content), areaWidth, areaHeight, escape(view.name)));
            }
            buf.append("</div>");

            // footer: text links for everything that can be zoomed into from here
            buf.append("<div class=\"footer\">");
            List<String> zoomLinks = new ArrayList<>();
            for (PdfLink link : view.links) {
                for (String targetKey : link.views) {
                    PdfView target = viewsByKey.get(targetKey);
                    if (target != null) {
                        zoomLinks.add("<a href=\"#" + target.anchor + "\">&#128269; " + escape(target.name) + "</a>");
                    }
                }
            }
            if (!zoomLinks.isEmpty()) {
                buf.append("Zoom in: ").append(String.join(" &nbsp; ", new LinkedHashSet<>(zoomLinks)));
            }
            buf.append("</div>");

            buf.append("</section>");
        }

        buf.append("</body></html>");

        return buf.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }

        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static class PdfView {

        String anchor;
        String key;
        String name;
        String description;
        String content;
        double width;
        double height;
        final List<PdfLink> links = new ArrayList<>();

    }

    static class PdfLink {

        String name;
        double x;
        double y;
        double width;
        double height;
        final List<String> views = new ArrayList<>();

    }

    private void save(DiagramFile diagram, File outputDir) {
        if (diagram != null && !StringUtils.isNullOrEmpty(diagram.getContent())) {
            try {
                File file = new File(outputDir, diagram.getFilename());
                log.info("Writing " + file.getCanonicalPath());
                String base64Image = diagram.getContent().split(",")[1];
                byte[] decodedImage = Base64.getDecoder().decode(base64Image.getBytes(StandardCharsets.UTF_8));
                Files.write(file.toPath(), decodedImage);
            } catch (IOException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    static class Diagram {

        private final DiagramFile diagram;
        private final DiagramFile key;

        Diagram(DiagramFile diagram, DiagramFile key) {
            this.diagram = diagram;
            this.key = key;
        }

        DiagramFile getDiagram() {
            return diagram;
        }

        DiagramFile getKey() {
            return key;
        }

        boolean hasKey() {
            return key != null;
        }

    }

    static class DiagramFile {

        private final String filename;
        private final String content;

        DiagramFile(String filename, String content) {
            this.filename = filename;
            this.content = content;
        }

        String getFilename() {
            return filename;
        }

        String getContent() {
            return content;
        }

    }

}