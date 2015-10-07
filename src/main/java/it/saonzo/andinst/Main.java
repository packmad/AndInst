package it.saonzo.andinst;

import kellinwood.security.zipsigner.ZipSigner;
import org.apache.commons.cli.*;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.reference.MethodReference;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {
    private static final String OPTION_DEFINSTR = "definstr";
    private static final String OPTION_OUTPUT = "output";
    private static final String OPTION_INPUT = "input";
    private static final int BADEXIT = -1;

    private final Map<MethodReference, MethodReference> redirections = new HashMap<>();
    private final Set<ClassDef> classesWithInstrMeths = new HashSet<>();
    private final IOutput out = new ConsoleOutput(IOutput.Level.DEBUG);

    private final CommandLine cmdLine;
    private final String instrDexFileName;
    private final String inApkFilename;
    private final String outApkFilename;

    private static class BadCommandLineException extends Exception {
        private BadCommandLineException () {
            super();
        }
    }

    public static void main(String[] args) {
        new Main(args);
    }

    private Main(String[] args) {
        cmdLine = parseCmdLine(args);
        inApkFilename = cmdLine.getOptionValue(OPTION_INPUT);
        outApkFilename = cmdLine.getOptionValue(OPTION_OUTPUT);
        instrDexFileName = cmdLine.getOptionValue(OPTION_DEFINSTR);
        try {
            checkFileHasApkExtension(inApkFilename);
            checkFileHasApkExtension(outApkFilename);
            checkFileHasApkExtension(instrDexFileName);
        } catch (BadCommandLineException e) {
            out.printf(IOutput.Level.ERROR, e.toString());
            System.exit(BADEXIT);
        }

        try {
            new InstrMethodsLoader(out).load(instrDexFileName, classesWithInstrMeths, redirections);
        } catch (IOException e) {
            out.printf(IOutput.Level.ERROR, e.toString());
            System.exit(BADEXIT);
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

    private void checkFileHasApkExtension(String filePath) throws BadCommandLineException {
        if (filePath != null) {
            File file = new File(filePath);
            if (file.isDirectory()) {
                out.printf(IOutput.Level.ERROR, "This is a path of a directory '%s'. File needed.", filePath);
                throw new BadCommandLineException();
            }
            final int indexOfDot = filePath.lastIndexOf(".");
            if (indexOfDot==-1 || !filePath.substring(indexOfDot).equalsIgnoreCase(".apk")) {
                out.printf(IOutput.Level.ERROR, "The extension of the file '%s' must be '.apk'.", filePath);
                throw new BadCommandLineException();
            }
        }
    }

    private void signApk(File tmpApkFile) throws Exception {
        ZipSigner zipSigner = new ZipSigner();
        zipSigner.setKeymode("auto-testkey");
        String inputFilename = tmpApkFile.getCanonicalPath();
        out.printf(IOutput.Level.VERBOSE, "Signing %s into %s\n", inputFilename, outApkFilename);
        zipSigner.signZip(inputFilename, outApkFilename);
    }

    private void writeApk(File tmpClassesDex, File outApkFile) throws IOException {
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

    private File customizeBytecode() throws IOException {
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

    private CommandLine parseCmdLine(String[] args) {
        final Options options = SetupOptions();
        CommandLine cmdline = null;
        boolean parsingError = false;
        try {
            cmdline = new GnuParser().parse(options, args);
        } catch (ParseException exp) {
            System.err.println(exp.getMessage());
            System.err.println();
            parsingError = true;
        }
        if (parsingError) {
            final HelpFormatter helpFormatter = new HelpFormatter();
            helpFormatter.printHelp("andinst", options, true);
            return null;
        }
        return cmdline;
    }

    private static Options SetupOptions() {
        Option i = new Option(OPTION_INPUT.substring(0, 1), "Input APK file");
        i.setArgs(1);
        i.setArgName("APK-filename");
        i.setLongOpt(OPTION_INPUT);
        i.setRequired(true);

        Option d = new Option(OPTION_DEFINSTR.substring(0, 1), "APK/Dex filename that contains instrumented methods");
        d.setArgs(1);
        d.setArgName("APK/DEX-filename");
        d.setLongOpt(OPTION_DEFINSTR);
        d.setRequired(true);

        Option o = new Option(OPTION_OUTPUT.substring(0, 1), "Output APK filename");
        o.setArgs(1);
        o.setArgName("APK-filename");
        o.setLongOpt(OPTION_OUTPUT);
        o.setRequired(true);

        final Options options = new Options();
        options.addOption(i)
                .addOption(d)
                .addOption(o);
        return options;
    }
}
