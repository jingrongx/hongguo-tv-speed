package rocks.aston.hongguotv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

final class UpdateFiles {
    interface Monitor { void check() throws IOException; void progress(long bytes); }
    static void copyChecked(InputStream input, File partial, long size, String sha, Monitor monitor) throws Exception {
        writeChecked(input, partial, 0, size, sha, monitor);
    }

    /**
     * Streams the download into the partial file, optionally appending after
     * an interrupted attempt (resumeFrom &gt; 0). A fresh download keeps the
     * strict streamed SHA-256 check; a resumed one skips it because the
     * digest only covers the new bytes, and relies on the caller's full-file
     * archive verification before install. A failed resume keeps the partial
     * file so the next attempt continues from the same offset.
     */
    static void writeChecked(InputStream input, File partial, long resumeFrom, long size, String sha, Monitor monitor) throws Exception {
        boolean complete = false;
        try (FileOutputStream output = new FileOutputStream(partial, resumeFrom > 0)) {
            MessageDigest digest = resumeFrom > 0 ? null : MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768]; int count; long total = resumeFrom;
            while ((count = input.read(buffer)) != -1) {
                monitor.check(); total += count;
                if (total > size || total > UpdatePolicy.MAX_APK_BYTES) throw new IOException("安装包超过预期大小");
                output.write(buffer, 0, count); if (digest != null) digest.update(buffer, 0, count); monitor.progress(total);
            }
            monitor.check(); output.getFD().sync();
            if (resumeFrom == 0 && (total != size || !UpdatePolicy.hex(digest.digest()).equals(sha))) throw new IOException("安装包完整性校验失败");
            complete = true;
        } finally { if (!complete && resumeFrom == 0) partial.delete(); }
    }
}
