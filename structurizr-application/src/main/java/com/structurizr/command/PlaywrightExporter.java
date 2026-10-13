package com.structurizr.command;

import com.structurizr.view.ColorScheme;

import java.io.File;

public interface PlaywrightExporter {

    /**
     * Creates the Playwright-based exporter, or returns null if this build doesn't include it.
     */
    static PlaywrightExporter create() {
        try {
            Class<?> clazz = Class.forName(PlaywrightExporter.class.getName() + "Impl");
            return (PlaywrightExporter)clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    void run(String url, String format, ColorScheme colorScheme, boolean animation, File outputDir);

    void runPdf(String url, ColorScheme colorScheme, File outputFile);

    /**
     * Renders a clickable PDF from views exported by the diagram viewer, as a JSON array of
     * { key, name, description, content (image data URI), links: [ { name, x, y, width, height, views: [key] } ] }.
     */
    byte[] renderPdf(String viewsAsJson, ColorScheme colorScheme);

}