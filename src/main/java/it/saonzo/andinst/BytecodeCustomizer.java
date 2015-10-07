package it.saonzo.andinst;


import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction35c;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableMethod;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.*;

class BytecodeCustomizer {
    private final Set<ClassDef> classesWithInstrMeths;
    private final Map<MethodReference, MethodReference> redirections;
    private final File inputFile;
    private final File outputDex;
    private final IOutput out;
    private int nInstrumented;

    public BytecodeCustomizer(IOutput out,
                              File inputFile,
                              Set<ClassDef> classesWithInstrMeths,
                              Map<MethodReference, MethodReference> redirections,
                              File outputDex
    ) {
        this.out = out;
        this.inputFile = inputFile;
        this.classesWithInstrMeths = classesWithInstrMeths;
        this.redirections = redirections;
        this.outputDex = outputDex;
    }

    public void customize() throws IOException {
        nInstrumented = 0;
        final List<ClassDef> classes = new ArrayList<>(classesWithInstrMeths);
        DexFile dexFile = DexFileFactory.loadDexFile(inputFile, 19, false);
        for (ClassDef classDef : dexFile.getClasses())
            classes.add(customizeClass(classDef));
        out.printf(IOutput.Level.ERROR, "Instrumented %d invocation(s)\n", nInstrumented);
        writeDexFile(classes);
    }

    private void writeDexFile(final List<ClassDef> classes) throws IOException {
        String outputFilename = outputDex.getCanonicalPath();
        out.printf(IOutput.Level.DEBUG, "Writing DEX-file: %s\n", outputFilename);
        DexFileFactory.writeDexFile(outputFilename, new DexFile() {
            @Override
            @Nonnull
            public Set<? extends ClassDef> getClasses() {
                return new AbstractSet<ClassDef>() {
                    @Nonnull
                    @Override
                    public Iterator<ClassDef> iterator() {
                        return classes.iterator();
                    }

                    @Override
                    public int size() {
                        return classes.size();
                    }
                };
            }
        });
    }

    private ClassDef customizeClass(ClassDef classDef) {
        List<Method> methods = new ArrayList<>();
        boolean modifiedMethod = false;
        for (Method method : classDef.getMethods()) {
            MethodImplementation implementation = method.getImplementation();
            if (implementation == null) {
                methods.add(method);
                continue;
            }
            MethodImplementation customImpl = searchAndReplaceInvocations(implementation);
            if (customImpl==implementation) {
                methods.add(method);
                continue;
            }
            modifiedMethod = true;
            final ImmutableMethod newMethod = new ImmutableMethod(method.getDefiningClass(),
                                                                  method.getName(),
                                                                  method.getParameters(),
                                                                  method.getReturnType(),
                                                                  method.getAccessFlags(),
                                                                  method.getAnnotations(),
                                                                  customImpl);
            methods.add(newMethod);
        }
        if (!modifiedMethod)
            return classDef;
        return new ImmutableClassDef(classDef.getType(),
                                     classDef.getAccessFlags(),
                                     classDef.getSuperclass(),
                                     classDef.getInterfaces(),
                                     classDef.getSourceFile(),
                                     classDef.getAnnotations(),
                                     classDef.getFields(),
                                     methods);
    }

    private MethodImplementation searchAndReplaceInvocations(MethodImplementation origImplementation) {
        MutableMethodImplementation newImplementation = null;
        int i = -1;
        for (Instruction instruction : origImplementation.getInstructions()) {
            ++i;
            Opcode opcode = instruction.getOpcode();
            if (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_STATIC) {
                assert instruction instanceof Instruction35c;
                Instruction35c invokeVirtualInstruction = (Instruction35c) instruction;
                Instruction35c newInstruction = checkInstruction(invokeVirtualInstruction);
                if (newInstruction == invokeVirtualInstruction)
                    continue;
                if (newImplementation == null)
                    newImplementation = new MutableMethodImplementation(origImplementation);
                ++nInstrumented;
                newImplementation.replaceInstruction(i, (BuilderInstruction35c)newInstruction);
            }
        }
        return newImplementation!=null ? newImplementation : origImplementation;
    }

    private Instruction35c checkInstruction(Instruction35c invokeInstr) {
        MethodReference r = (MethodReference) invokeInstr.getReference();
        // out.printf(IOutput.Level.DEBUG, "Method %s . %s\n", r.getDefiningClass(), r.getName());
        MethodReference redirection = redirections.get(r);
        if (redirection != null) {
            out.printf(IOutput.Level.DEBUG, "Applying redirection to %s\n", redirection);
            return new BuilderInstruction35c(Opcode.INVOKE_STATIC,
                                             invokeInstr.getRegisterCount(),
                                             invokeInstr.getRegisterC(),
                                             invokeInstr.getRegisterD(),
                                             invokeInstr.getRegisterE(),
                                             invokeInstr.getRegisterF(),
                                             invokeInstr.getRegisterG(),
                                             redirection);
        }
        return invokeInstr;
    }

}
