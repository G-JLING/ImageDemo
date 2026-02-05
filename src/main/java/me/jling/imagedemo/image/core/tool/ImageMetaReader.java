package me.jling.imagedemo.image.core.tool;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

public class ImageMetaReader {

    public static ImageInfo read(byte[] bytes) {
        int w=0, h=0;
        try (var in = new ByteArrayInputStream(bytes)) {
            BufferedImage bi = ImageIO.read(in);
            if (bi != null) { w = bi.getWidth(); h = bi.getHeight(); }
        } catch (Exception ignore){}

        Double lat=null,lng=null; String make=null,model=null;
        java.time.Instant ts=null; String place=null;
        try {
            var meta = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            for (Directory dir : meta.getDirectories()) {
                if (dir instanceof GpsDirectory gps) {
                    var loc = gps.getGeoLocation();
                    if (loc != null && !loc.isZero()) { lat = loc.getLatitude(); lng = loc.getLongitude(); }
                }
                if (dir.containsTag(ExifIFD0Directory.TAG_MAKE))   make  = dir.getString(ExifIFD0Directory.TAG_MAKE);
                if (dir.containsTag(ExifIFD0Directory.TAG_MODEL))  model = dir.getString(ExifIFD0Directory.TAG_MODEL);
                if (dir.containsTag(0x9003)) {
                    var s = dir.getString(0x9003);
                    ts = java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                            .atZone(java.time.ZoneId.systemDefault()).toInstant();
                }
            }
            // 有经纬度时可用逆地理服务补充 placeText
        } catch (Exception ignore){}

        return new ImageInfo(w, h, lat, lng, place, ts, make, model);
    }
}
