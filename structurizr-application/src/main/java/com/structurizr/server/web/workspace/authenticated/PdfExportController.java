package com.structurizr.server.web.workspace.authenticated;

import com.structurizr.command.PlaywrightExporter;
import com.structurizr.server.domain.WorkspaceMetadata;
import com.structurizr.view.ColorScheme;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/**
 * Renders the views exported by the diagram viewer into a single, clickable PDF.
 */
@Controller
class PdfExportController extends AbstractWorkspaceController {

    private static final Log log = LogFactory.getLog(PdfExportController.class);

    @RequestMapping(value = "/workspace/{workspaceId}/pdf", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportPdf(@PathVariable("workspaceId") long workspaceId,
                                            @RequestParam(required = false, defaultValue = "light") String mode,
                                            @RequestBody String views) {
        WorkspaceMetadata workspaceMetadata = workspaceComponent.getWorkspaceMetadata(workspaceId);
        if (workspaceMetadata == null || workspaceMetadata.getPermissions(getUser()).isEmpty()) {
            return ResponseEntity.status(HttpServletResponse.SC_NOT_FOUND).build();
        }

        PlaywrightExporter exporter = PlaywrightExporter.create();
        if (exporter == null) {
            return ResponseEntity.status(HttpServletResponse.SC_NOT_IMPLEMENTED).build();
        }

        try {
            ColorScheme colorScheme = "dark".equalsIgnoreCase(mode) ? ColorScheme.Dark : ColorScheme.Light;
            byte[] pdf = exporter.renderPdf(views, colorScheme);
            if (pdf == null) {
                return ResponseEntity.badRequest().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"structurizr-" + workspaceId + ".pdf\"")
                    .body(pdf);
        } catch (Exception e) {
            log.error("Error exporting PDF", e);
            return ResponseEntity.internalServerError().build();
        }
    }

}
