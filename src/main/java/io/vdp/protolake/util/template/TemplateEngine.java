package io.vdp.protolake.util.template;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Qute-based template engine for generating configuration files.
 *
 * This replaces the previous simple regex-based implementation to support
 * the full Qute template syntax including conditionals and loops.
 */
@ApplicationScoped
public class TemplateEngine {
    private static final Logger LOG = Logger.getLogger(TemplateEngine.class);

    @Inject
    Engine quteEngine;

    /**
     * Renders a template from resources with the given context.
     *
     * @param templatePath Path to template in resources/templates
     * @param context Variables to substitute
     * @return Rendered template
     */
    public String render(String templatePath, Map<String, Object> context) throws IOException {
        String template = loadTemplate(templatePath);
        return renderString(template, context);
    }

    /**
     * Renders a template string with the given context.
     *
     * @param template Template string
     * @param context Variables to substitute
     * @return Rendered template
     */
    public String renderString(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        // Create a Qute template instance
        Template quteTemplate = quteEngine.parse(template);
        TemplateInstance instance = quteTemplate.instance();

        // Add all context variables to the template
        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                instance.data(entry.getKey(), entry.getValue());
            }
        }

        // Render the template
        String result = instance.render();
        LOG.debugf("Rendered template successfully");

        return result;
    }

    /**
     * Loads a template from resources.
     */
    private String loadTemplate(String templatePath) throws IOException {
        String fullPath = "/templates/" + templatePath;

        try (InputStream is = getClass().getResourceAsStream(fullPath)) {
            if (is == null) {
                throw new IOException("Template not found: " + fullPath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Renders a template from resources and writes it to a file.
     *
     * @param templatePath Path to template in resources/templates
     * @param context Variables to substitute
     * @param outputPath Path where to write the rendered template
     * @throws IOException if template loading or file writing fails
     */
    public void renderToFile(String templatePath, Map<String, Object> context, Path outputPath) throws IOException {
        String rendered = render(templatePath, context);

        // Ensure parent directory exists
        Files.createDirectories(outputPath.getParent());

        // Write the rendered content
        Files.writeString(outputPath, rendered, StandardCharsets.UTF_8);

        LOG.debugf("Rendered template %s to %s", templatePath, outputPath);
    }

    /**
     * Copies a resource file without template processing.
     *
     * This is useful for scripts, configuration files, or other resources that
     * should be copied as-is without any template variable substitution.
     *
     * @param resourcePath Path to resource in resources/templates
     * @param outputPath Path where to copy the resource
     * @throws IOException if resource loading or file writing fails
     */
    public void copyResource(String resourcePath, Path outputPath) throws IOException {
        String fullPath = "/templates/" + resourcePath;

        try (InputStream is = getClass().getResourceAsStream(fullPath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + fullPath);
            }

            // Ensure parent directory exists
            Files.createDirectories(outputPath.getParent());

            // Copy the resource as-is without any processing
            Files.copy(is, outputPath, StandardCopyOption.REPLACE_EXISTING);

            LOG.debugf("Copied resource %s to %s", resourcePath, outputPath);
        }
    }
}