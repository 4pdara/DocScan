import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Small previews of generated UI fixtures, readable through private CI text logs. */
class ReviewScreenshots {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        if (!Files.isDirectory(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("workflow-") && p.toString().endsWith(".png")).sorted().toList()) {
                BufferedImage source = ImageIO.read(path.toFile());
                if (source == null) continue;
                int width = Math.min(360, source.getWidth());
                int height = Math.max(1, source.getHeight() * width / source.getWidth());
                BufferedImage preview = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var graphics = preview.createGraphics();
                graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, width, height, null); graphics.dispose();
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(preview, "jpg", bytes);
                String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
                String name = path.getFileName().toString();
                System.out.println("DOCSCAN_REVIEW_START:" + name);
                for (int offset = 0; offset < encoded.length(); offset += 1500) {
                    System.out.println("DOCSCAN_REVIEW_PART:" + name + ":" + encoded.substring(offset, Math.min(offset + 1500, encoded.length())));
                }
                System.out.println("DOCSCAN_REVIEW_END:" + name);
            }
        }
    }
}
