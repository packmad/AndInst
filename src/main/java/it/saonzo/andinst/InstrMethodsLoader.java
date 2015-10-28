package it.saonzo.andinst;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.dexbacked.value.DexBackedStringEncodedValue;
import org.jf.dexlib2.immutable.value.ImmutableBooleanEncodedValue;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.reference.MethodReference;

import java.io.IOException;
import java.util.*;

class InstrMethodsLoader {
    private static final String CLASS_ANNOTATION = "Lit/saonzo/annotations/ClassWithInstrMethods;";
    private static final String METHOD_ANNOTATION = "Lit/saonzo/annotations/InstrumentedMethod;";

    private static final String METHOD_ANNOTATION_ISINIT_ELEMENT = "isInit";
    private static final String METHOD_ANNOTATION_ISSTATIC_ELEMENT = "isStatic";
    private static final String METHOD_ANNOTATION_DEFCLASS_ELEMENT = "defClass";
    private final IOutput out;


    public InstrMethodsLoader(IOutput out) { this.out = out; }

    public void load(String dexFileName, Set<ClassDef> classesWithInstrMeths, Map<MethodReference, MethodReference> redirections) throws IOException {
        out.printf(IOutput.Level.VERBOSE, "Loading custom methods from %s\n", dexFileName);
        DexFile dexFile = DexFileFactory.loadDexFile(dexFileName, 19, false);
        for (ClassDef classDef : dexFile.getClasses()) {
            boolean added = false;
            for (Annotation a : classDef.getAnnotations()) {
                if (a.getType().equals(CLASS_ANNOTATION)) {
                    if (AccessFlags.PUBLIC.isSet(classDef.getAccessFlags())) {
                        added = classesWithInstrMeths.add(classDef);
                    }
                    else
                        PrintWarning("ignoring class " + classDef + " because it is not public");
                }
            }
            if (added) {
                for (Method method : classDef.getMethods()) {
                    for (Annotation a : method.getAnnotations()) {
                        if (a.getType().equals(METHOD_ANNOTATION)) {

                            boolean isStatic = false;
                            boolean isInit = false;
                            String annDefClass = "";

                            for (AnnotationElement element : a.getElements()) {
                                final String elementName = element.getName();
                                switch (elementName) {
                                    case METHOD_ANNOTATION_ISINIT_ELEMENT:
                                        isInit = ((ImmutableBooleanEncodedValue) element.getValue()).getValue();
                                        break;
                                    case METHOD_ANNOTATION_ISSTATIC_ELEMENT:
                                        isStatic = ((ImmutableBooleanEncodedValue) element.getValue()).getValue();
                                        break;
                                    case METHOD_ANNOTATION_DEFCLASS_ELEMENT:
                                        annDefClass = DexMethod.fromJavaTypeToDalvikType(((DexBackedStringEncodedValue) element.getValue()).getValue());
                                        break;
                                    default:
                                        assert false;
                                        break;
                                }
                            }
                            final String methodName = method.getName();
                            final String definingClass = method.getDefiningClass();
                            final List<? extends CharSequence> parameterTypes = method.getParameterTypes();
                            final String fullMethodName = definingClass + "." + methodName;
                            if (isInit && isStatic) {
                                PrintWarning("ignoring " + fullMethodName + " because it's annotated static and init");
                                continue;
                            }
                            if (!isStatic && parameterTypes.isEmpty()) {
                                PrintWarning("ignoring non static" + fullMethodName + " because its signature is missing the 'this' parameter\n");
                                continue;
                            }
                            final String firstParam = parameterTypes.get(0).toString();
                            if (!isStatic && !firstParam.equals(annDefClass)) {
                                PrintWarning("ignoring non static" + fullMethodName + " because its first param is different from defclass annotation\n");
                                continue;
                            }
                            final String returnType = method.getReturnType();
                            String origMethName = methodName;
                            if (isInit)
                                origMethName = "<init>";
                            final DexMethod originalMethod = new DexMethod(
                                    annDefClass,
                                    origMethName,
                                    removeThisParam(parameterTypes, isStatic),
                                    returnType);
                            final DexMethod newMethod = new DexMethod(
                                    method.getDefiningClass(),
                                    methodName,
                                    parameterTypes,
                                    returnType);
                            out.printf(IOutput.Level.DEBUG,
                                    "Adding redirection: %s ---> %s\n",
                                    originalMethod,
                                    newMethod);
                            redirections.put(originalMethod, newMethod);
                        }
                    }
                }
            }
        }

    }


    private ArrayList<String> removeThisParam(List<? extends CharSequence> parameterTypes, boolean isStatic) {
        ArrayList<String> result = new ArrayList<>();
        for (CharSequence cs : parameterTypes)
            result.add(cs.toString());
        if (!isStatic) {
            final int THIS_PARAM_INDEX = 0;
            result.remove(THIS_PARAM_INDEX);
        }
        return result;
    }


    private void PrintWarning(String msg) {
        out.printf(IOutput.Level.NORMAL, "Warning: %s", msg);
    }

}
