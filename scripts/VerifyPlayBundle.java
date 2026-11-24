import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.jar.*;

/** Verify every AAB payload entry against the pinned upload certificate. */
class VerifyPlayBundle {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Expected bundle and SHA-256 fingerprint");
        String expected = args[1].toLowerCase(Locale.ROOT);
        int count = 0;
        try (JarFile jar = new JarFile(args[0], true)) {
            if (jar.getManifest() == null) throw new SecurityException("Missing signature manifest");
            Set<String> names = new HashSet<>();
            byte[] buffer = new byte[65536];
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!names.add(entry.getName())) throw new SecurityException("Duplicate bundle entry");
                if (entry.isDirectory()) continue;
                String name = entry.getName().toUpperCase(Locale.ROOT);
                // Exclude only standard JAR signature metadata, not arbitrary META-INF payloads.
                if (name.equals("META-INF/MANIFEST.MF") ||
                    name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)") ||
                    name.matches("META-INF/SIG-[^/]+")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    while (in.read(buffer) != -1) { }
                }
                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1)
                    throw new SecurityException("Missing or unexpected signers: " + entry.getName());
                Certificate leaf = signers[0].getSignerCertPath().getCertificates().get(0);
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
                if (!actual.equals(expected)) throw new SecurityException("Bundle certificate mismatch");
                count++;
            }
            if (count == 0 || jar.getJarEntry("BundleConfig.pb") == null ||
                jar.getJarEntry("base/manifest/AndroidManifest.xml") == null)
                throw new SecurityException("Incomplete AAB");
        }
        System.out.println("AAB_SIGNING_IDENTITY=verified entries=" + count + " certificate_sha256=" + expected);
    }
}
