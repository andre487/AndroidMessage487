import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.jar.JarFile;

/** Verify every payload entry, including entries appended after signing. */
class VerifyBundle {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: VerifyBundle.java bundle.aab SHA256|unsigned");
        boolean unsigned = args[1].equals("unsigned");
        var required = new java.util.HashSet<>(Set.of(
            "BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/resources.pb", "base/dex/classes.dex"));
        int payloadEntries = 0;
        try (var jar = new JarFile(Path.of(args[0]).toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (var input = jar.getInputStream(entry)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                required.remove(entry.getName());
                String name = entry.getName().toUpperCase(java.util.Locale.ROOT);
                if (name.equals("META-INF/MANIFEST.MF") ||
                    name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)") ||
                    name.matches("META-INF/SIG-[^/]+")) continue;
                payloadEntries++;
                var certificates = entry.getCertificates();
                if (unsigned) {
                    if (certificates != null && certificates.length > 0) throw new SecurityException("Unexpected signed bundle");
                } else {
                    if (certificates == null || certificates.length != 1) throw new SecurityException("Unsigned or unexpected bundle entry");
                    var digest = MessageDigest.getInstance("SHA-256").digest(certificates[0].getEncoded());
                    if (!HexFormat.of().formatHex(digest).equals(args[1])) throw new SecurityException("Unexpected bundle signing certificate");
                }
            }
        }
        if (!required.isEmpty() || payloadEntries == 0) throw new SecurityException("Incomplete app bundle");
        System.out.println("Verified " + (unsigned ? "unsigned" : "signed") + " App Bundle");
    }
}
