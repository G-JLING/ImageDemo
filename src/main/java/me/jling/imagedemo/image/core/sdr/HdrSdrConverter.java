package me.jling.imagedemo.image.core.sdr;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class HdrSdrConverter {

    @Value("${heif.cli.timeout-sec:120}")
    private int heifCliTimeoutSec;

    @Value("${heif.cli.ffmpegPath:ffmpeg}")
    private String ffmpegPath;

    @Value("${heif.cli.exiftoolPath:exiftool}")
    private String exiftoolPath;

    @Value("${heif.cli.ffprobePath:ffprobe}")
    private String ffprobePath;

    @Value("${heif.cli.heifConvertPath:heif-convert}")
    private String heifConvertPath;

    @Value("${heif.cli.gainmapMergePy:}")
    private String gainmapMergePy;

    /**
     * 读取原图并按 EXIF Orientation 纠正方向（仅处理 3/6/8）。
     */
    public BufferedImage readOrigNormalized(Path p) throws IOException {
        BufferedImage img = readAny(p);
        if (img == null) {
            throw new IOException("Unable to decode image: " + p);
        }

        log.debug("[readOrigNormalized] decoded {} -> {}x{}", p.getFileName(), img.getWidth(), img.getHeight());

        int orientation = 1;
        try {
            Metadata md = ImageMetadataReader.readMetadata(p.toFile());
            ExifIFD0Directory d0 = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (d0 != null && d0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = d0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            log.debug("读取 EXIF 方向失败，按默认方向处理: {} {}", p, e.toString());
        }

        return switch (orientation) {
            case 3 -> rotate(img, 180);
            case 6 -> rotate(img, 90);
            case 8 -> rotate(img, 270);
            default -> img;
        };
    }

    private BufferedImage readAny(Path p) throws IOException {
        log.info("[readAny] enter file={} ext={} heifOrAvif?={} ", p.getFileName(), extLower(p), safeIsHeifOrAvif(p));

        BufferedImage bi = null;

        try {
            bi = ImageIO.read(p.toFile());
            if (bi != null) return bi;
        } catch (Exception ignore) {}

        boolean forceFfmpeg = safeIsHeifOrAvif(p);
        if (forceFfmpeg && (ffmpegPath == null || ffmpegPath.isBlank())) {
            log.warn("[readAny] ImageIO failed or forced-ffmpeg, but ffmpegPath is blank for {}", p);
            return null;
        }

        int[] expected = probeDimensionsByFfprobe(p);
        int mapIndex = pickBestVideoStream(p)[0];
        log.info("[readAny] expect={}x{} map=0:v:{} (ffprobe)", expected[0], expected[1], mapIndex);

        Path tmpHdrPng = tryDecodeHeifAvifToSdrPng(p, mapIndex);
        if (tmpHdrPng != null && Files.exists(tmpHdrPng)) {
            try {
                BufferedImage biHdr = ImageIO.read(tmpHdrPng.toFile());
                if (biHdr != null) {
                    log.info("[readAny] HDR tonemapped decode -> {}x{} for {}", biHdr.getWidth(), biHdr.getHeight(), p.getFileName());
                    return biHdr;
                }
            } catch (Exception e) {
                log.warn("[readAny] read HDR tmp failed for {}: {}", p.getFileName(), e.toString());
            } finally {
                try { Files.deleteIfExists(tmpHdrPng); } catch (Exception ignore) {}
            }
        }

        Path tmpHc = null;
        if (heifConvertPath != null && !heifConvertPath.isBlank()) {
            try {
                tmpHc = Files.createTempFile("readAny-hc-first-", ".png");
                int ecHc = runCmd(java.util.List.of(
                        heifConvertPath, p.toString(), tmpHc.toString()
                ), heifCliTimeoutSec);
                if (ecHc == 0 && Files.exists(tmpHc)) {
                    BufferedImage bi2 = ImageIO.read(tmpHc.toFile());
                    if (bi2 != null) {
                        log.info("[readAny] heif-convert primary -> {}x{} for {} (preferred for HEIF/AVIF)",
                                bi2.getWidth(), bi2.getHeight(), p.getFileName());
                        return bi2;
                    }
                } else {
                    log.warn("[readAny] heif-convert-first exit={} for {}", ecHc, p.getFileName());
                }
            } catch (Exception e) {
                log.warn("[readAny] heif-convert read failed for {}: {}", p.getFileName(), e.toString());
            } finally {
                if (tmpHc != null) try { Files.deleteIfExists(tmpHc); } catch (Exception ignore) {}
            }
        }

        Path tmp = Files.createTempFile("readAny-ff-", ".png");
        Path tmp2 = null;
        try {
            String ct2 = probeColorTransfer(p, mapIndex);
            String filter = "scale=trunc(iw/2)*2:trunc(ih/2)*2";
            if (ct2 != null && !ct2.isBlank()) {
                String ct = ct2.toLowerCase();
                if (ct.contains("smpte2084") || ct.contains("arib-std-b67")) {
                    filter += ",zscale=transfer=pq:matrix=bt2020nc:primaries=bt2020,tonemap=linear:desat=0";
                }
            }

            int ec = runCmd(java.util.List.of(
                    ffmpegPath,
                    "-y",
                    "-i", p.toString(),
                    "-map", "0:v:" + mapIndex,
                    "-vf", filter,
                    "-frames:v", "1",
                    tmp.toString()
            ), heifCliTimeoutSec);

            if (ec == 0 && Files.exists(tmp)) {
                BufferedImage bi2 = ImageIO.read(tmp.toFile());
                if (bi2 != null) {
                    int w2 = bi2.getWidth();
                    int h2 = bi2.getHeight();
                    if (expected[0] == 0 || expected[1] == 0) {
                        log.info("[readAny] ffmpeg OK: using decoded frame {}", p.getFileName());
                        return bi2;
                    }
                    double ratio = Math.max(
                            (double) w2 / Math.max(1, expected[0]),
                            (double) h2 / Math.max(1, expected[1])
                    );
                    if (ratio > 0.7 && ratio < 1.4) {
                        log.info("[readAny] ffmpeg map 0:v:{} -> decoded {}x{} (expected {}x{})", mapIndex, w2, h2, expected[0], expected[1]);
                        return bi2;
                    }
                    log.warn("[readAny] mismatch (ratio={}) ffmpeg {}x{} vs expected {}x{} for {} — fallback to heif-convert",
                            ratio, w2, h2, expected[0], expected[1], p.getFileName());
                }
            } else {
                log.warn("[readAny] ffmpeg exit={} or no tmp for {}", ec, p);
            }

            if (heifConvertPath != null && !heifConvertPath.isBlank()) {
                tmp2 = Files.createTempFile("readAny-hc-", ".png");
                int ec2 = runCmd(java.util.List.of(
                        heifConvertPath, p.toString(), tmp2.toString()
                ), heifCliTimeoutSec);
                if (ec2 == 0 && Files.exists(tmp2)) {
                    BufferedImage bi3 = ImageIO.read(tmp2.toFile());
                    if (bi3 != null) {
                        log.info("[readAny] heif-convert OK -> {}x{} for {}", bi3.getWidth(), bi3.getHeight(), p.getFileName());
                        return bi3;
                    }
                } else {
                    log.warn("[readAny] heif-convert exit={} for {}", ec2, p);
                }
            } else {
                log.warn("[readAny] heif-convert path blank; cannot fallback for {}", p);
            }
            return null;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            if (tmp2 != null) try { Files.deleteIfExists(tmp2); } catch (Exception ignore) {}
        }
    }

    private int[] probeDimensionsByFfprobe(Path src) {
        try {
            if (ffprobePath == null || ffprobePath.isBlank()) return new int[]{0,0};
            var res = runCmdCapture(java.util.List.of(
                    ffprobePath,
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=s=x:p=0",
                    src.toString()
            ), heifCliTimeoutSec);
            int exit = res.getKey();
            String out = res.getValue() == null ? "" : res.getValue().trim();
            if (exit == 0 && !out.isBlank()) {
                String line = out.split("\r?\n")[0].trim();
                String[] parts = line.split("[x,]");
                if (parts.length >= 2) {
                    try {
                        int w = Integer.parseInt(parts[0].trim());
                        int h = Integer.parseInt(parts[1].trim());
                        if (w > 0 && h > 0) {
                            return new int[]{w, h};
                        }
                    } catch (NumberFormatException ignore) { }
                }
            } else if (exit != 0) {
                log.warn("[ffprobe] exit={} for {} output=\n{}", exit, src, out);
            }
        } catch (Exception e) {
            log.warn("[ffprobe] failed for {}: {}", src, e.toString());
        }
        return new int[]{0,0};
    }

    private int[] pickBestVideoStream(Path src) {
        if (ffprobePath == null || ffprobePath.isBlank()) return new int[]{0,0,0};
        try {
            var res = runCmdCapture(java.util.List.of(
                    ffprobePath,
                    "-v", "error",
                    "-select_streams", "v",
                    "-show_entries", "stream=index,width,height",
                    "-of", "csv=s=x:p=0",
                    src.toString()
            ), heifCliTimeoutSec);
            int exit = res.getKey();
            String out = res.getValue() == null ? "" : res.getValue().trim();
            if (exit != 0 || out.isBlank()) {
                log.warn("[ffprobe] pickBest exit={} out=\n{}", exit, out);
                return new int[]{0,0,0};
            }
            int bestIdx = 0, bestW = 0, bestH = 0, bestArea = 0;
            for (String line : out.split("\r?\n")) {
                String[] parts = line.trim().split("[x,]");
                if (parts.length < 3) continue;
                try {
                    int idx = Integer.parseInt(parts[0].trim());
                    int w   = Integer.parseInt(parts[1].trim());
                    int h   = Integer.parseInt(parts[2].trim());
                    int area = w * h;
                    if (w > 0 && h > 0 && area > bestArea) { bestArea = area; bestIdx = idx; bestW = w; bestH = h; }
                } catch (NumberFormatException ignore) { }
            }
            if (bestArea > 0) {
                log.info("[ffprobe] best stream index={} size={}x{}", bestIdx, bestW, bestH);
                return new int[]{bestIdx, bestW, bestH};
            }
        } catch (Exception e) {
            log.warn("[ffprobe] pickBest failed for {}: {}", src, e.toString());
        }
        return new int[]{0,0,0};
    }

    private String probeColorTransfer(Path src, int streamIndex) {
        if (ffprobePath == null || ffprobePath.isBlank()) return "";
        try {
            var res = runCmdCapture(java.util.List.of(
                    ffprobePath,
                    "-v", "error",
                    "-select_streams", "v:" + streamIndex,
                    "-show_entries", "stream=color_transfer",
                    "-of", "default=nw=1:nk=1",
                    src.toString()
            ), heifCliTimeoutSec);
            if (res.getKey() == 0) {
                String out = res.getValue() == null ? "" : res.getValue().trim();
                if (out.contains("=")) out = out.substring(out.indexOf('=') + 1).trim();
                return out;
            }
        } catch (Exception e) {
            log.debug("[ffprobe] probeColorTransfer failed for {}: {}", src.getFileName(), e.toString());
        }
        return "";
    }

    private boolean hasHdrGainMap(Path src) {
        try {
            if (exiftoolPath == null || exiftoolPath.isBlank()) return false;
            var res = runCmdCapture(java.util.List.of(
                    exiftoolPath,
                    "-s",
                    "-G1",
                    "-HDRGainMap*",
                    src.toString()
            ), 20);
            String out = res.getValue() == null ? "" : res.getValue().trim();
            return res.getKey() == 0 && !out.isBlank();
        } catch (Exception e) {
            log.debug("[exiftool] hasHdrGainMap failed for {}: {}", src.getFileName(), e.toString());
            return false;
        }
    }

    private Path tryDecodeHeifAvifToSdrPng(Path src, int mapIndex) {
        boolean hdr = false;
        String ct = probeColorTransfer(src, mapIndex);
        if ("smpte2084".equalsIgnoreCase(ct) || "arib-std-b67".equalsIgnoreCase(ct)) hdr = true;
        if (!hdr && hasHdrGainMap(src)) hdr = true;
        if (!hdr) return null;

        try {
            Path tmp = Files.createTempFile("readAny-tonemap-", ".png");

            try {
                if (gainmapMergePy != null && !gainmapMergePy.isBlank()) {
                    int ecPy = runCmd(java.util.List.of(
                            "python3",
                            gainmapMergePy,
                            src.toString(),
                            tmp.toString()
                    ), heifCliTimeoutSec);
                    if (ecPy == 0 && Files.exists(tmp)) {
                        log.info("[readAny] gainmapMergePy OK -> {}", tmp.getFileName());
                        return tmp;
                    } else {
                        log.warn("[readAny] gainmapMergePy exit={} or no tmp for {}", ecPy, src.getFileName());
                    }
                }
            } catch (Exception e) {
                log.warn("[readAny] gainmapMergePy failed for {}: {}", src.getFileName(), e.toString());
            }

            int ec = runCmd(java.util.List.of(
                    ffmpegPath,
                    "-y",
                    "-i", src.toString(),
                    "-map", "0:v:" + mapIndex,
                    "-vf", "zscale=transfer=pq:matrix=bt2020nc:primaries=bt2020,tonemap=linear:desat=0",
                    "-frames:v", "1",
                    tmp.toString()
            ), heifCliTimeoutSec);
            if (ec == 0 && Files.exists(tmp)) {
                log.info("[readAny] ffmpeg tonemap OK -> {}", tmp.getFileName());
                return tmp;
            } else {
                log.warn("[readAny] ffmpeg tonemap exit={} or no tmp for {}", ec, src.getFileName());
            }
        } catch (Exception e) {
            log.warn("[readAny] tryDecodeHeifAvifToSdrPng failed for {}: {}", src.getFileName(), e.toString());
        }
        return null;
    }

    private boolean safeIsHeifOrAvif(Path p) {
        try { return isHeifOrAvif(p); } catch (Exception e) { return false; }
    }

    private boolean isHeifOrAvif(Path file) throws IOException {
        byte[] buf = new byte[64];
        try (InputStream in = Files.newInputStream(file)) {
            int n = in.read(buf);
            if (n < 12) return false;
        }
        String head = new String(buf, StandardCharsets.ISO_8859_1);
        int idx = head.indexOf("ftyp");
        if (idx < 0) return false;
        String brands = head.substring(idx + 4, Math.min(head.length(), idx + 16)).toLowerCase();
        return brands.contains("heic") || brands.contains("heix") || brands.contains("heim")
                || brands.contains("hevc") || brands.contains("avif");
    }

    private static String extLower(Path p) {
        String name = String.valueOf(p.getFileName());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static BufferedImage rotate(BufferedImage src, int deg) {
        if (src == null) throw new IllegalArgumentException("rotate: src == null");
        deg = ((deg % 360) + 360) % 360;
        if (deg == 0) return src;
        final int w = src.getWidth(), h = src.getHeight();
        final int outW = (deg == 90 || deg == 270) ? h : w;
        final int outH = (deg == 90 || deg == 270) ? w : h;
        BufferedImage dst = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform at = new AffineTransform();
        at.translate(outW / 2.0, outH / 2.0);
        at.rotate(Math.toRadians(deg));
        at.translate(-w / 2.0, -h / 2.0);
        g.drawImage(src, at, null);
        g.dispose();
        return dst;
    }

    private int runCmd(java.util.List<String> cmd, long timeoutSec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) {
            is.transferTo(bos);
        }
        try {
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Command timeout: " + String.join(" ", cmd));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted: " + String.join(" ", cmd));
        }
        String out = bos.toString(StandardCharsets.UTF_8);
        int exit = p.exitValue();
        if (exit != 0 && !out.isBlank()) {
            log.info("[heif-cli] non-zero exit output:\n{}", out);
        } else if (!out.isBlank()) {
            log.debug("[heif-cli] {}", out.trim());
        }
        return exit;
    }

    private java.util.AbstractMap.SimpleEntry<Integer, String> runCmdCapture(java.util.List<String> cmd, long timeoutSec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) {
            is.transferTo(bos);
        }
        try {
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Command timeout: " + String.join(" ", cmd));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted: " + String.join(" ", cmd));
        }
        String out = bos.toString(StandardCharsets.UTF_8);
        int exit = p.exitValue();
        if (exit != 0 && !out.isBlank()) {
            log.info("[heif-cli] non-zero exit output:\n{}", out);
        } else if (!out.isBlank()) {
            log.debug("[heif-cli] {}", out.trim());
        }
        return new java.util.AbstractMap.SimpleEntry<>(exit, out);
    }
}
