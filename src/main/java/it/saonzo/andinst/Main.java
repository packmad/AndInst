package it.saonzo.andinst;

import kellinwood.security.zipsigner.ZipSigner;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.reference.MethodReference;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {
    private static final IOutput out = new ConsoleOutput(IOutput.Level.DEBUG);
    private static final Set<ClassDef> classesWithInstrMeths = new HashSet<>();
    private static final Map<MethodReference, MethodReference> redirections = new HashMap<>();
    private static final String instrDexFileName = "/home/simo/AndroidStudioProjects/InstApp/app/build/outputs/apk/app-debug.apk";
    private static final String inApkFilename = "/home/simo/Desktop/instest/com.posteitaliane.postemobilestore.apk";
    private static final String outApkFilename = "/home/simo/Desktop/instest/instr.apk";

    public static void main(String[] args) {


        try {
            new InstrMethodsLoader(out).load(instrDexFileName, classesWithInstrMeths, redirections);
        } catch (IOException e) {
            out.printf(IOutput.Level.ERROR, e.toString());
        }

        if (classesWithInstrMeths.isEmpty() || redirections.isEmpty()) {
            out.printf(IOutput.Level.ERROR, "There where problems parsing the APK with instrument definitions");
            return;
        }

        File tmpApkFile = null;
        File tmpClassesDex = null;
        try {
            tmpApkFile = File.createTempFile("OutputApk", null);
            tmpApkFile.deleteOnExit();
            tmpClassesDex = customizeBytecode();
            writeApk(tmpClassesDex, tmpApkFile);
            signApk(tmpApkFile);
        } catch (Exception e) {
            out.printf(IOutput.Level.ERROR, "%s", e.toString());
        } finally {
            if (tmpApkFile != null)
                tmpApkFile.delete();
            if (tmpClassesDex != null)
                tmpClassesDex.delete();
        }

    }

    private static void signApk(File tmpApkFile) throws Exception {
        ZipSigner zipSigner = new ZipSigner();
        zipSigner.setKeymode("auto-testkey");
        String inputFilename = tmpApkFile.getCanonicalPath();
        out.printf(IOutput.Level.VERBOSE, "Signing %s into %s\n", inputFilename, outApkFilename);
        zipSigner.signZip(inputFilename, outApkFilename);
    }

    private static void writeApk(File tmpClassesDex, File outApkFile) throws IOException {
        out.printf(IOutput.Level.VERBOSE, "Writing APK %s\n", outApkFile);
        JarFile inputJar = new JarFile(inApkFilename);
        try(ZipOutputStream outputJar = new ZipOutputStream(new FileOutputStream(outApkFile))) {
            Enumeration<JarEntry> entries = inputJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                final String name = entry.getName();
                outputJar.putNextEntry(new ZipEntry(name));
                if (name.equalsIgnoreCase("classes.dex"))
                    try (final FileInputStream newClasses = new FileInputStream(tmpClassesDex)) {
                        StreamUtils.copy(newClasses, outputJar);
                    }
                else
                    try (final InputStream entryInputStream = inputJar.getInputStream(entry)) {
                        StreamUtils.copy(entryInputStream, outputJar);
                    }
                outputJar.closeEntry();
            }
        }
    }

    private static File customizeBytecode() throws IOException {
        File tmpClassesDex;
        tmpClassesDex = File.createTempFile("NewClasseDex", null);
        tmpClassesDex.deleteOnExit();
        BytecodeCustomizer c = new BytecodeCustomizer(
                out,
                new File(inApkFilename),
                classesWithInstrMeths,
                redirections,
                tmpClassesDex
                );
        c.customize();
        return tmpClassesDex;
    }
}
