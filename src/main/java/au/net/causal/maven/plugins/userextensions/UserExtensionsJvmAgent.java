package au.net.causal.maven.plugins.userextensions;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.security.ProtectionDomain;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class UserExtensionsJvmAgent
{
    public static void premain(String agentArgs, Instrumentation inst)
    {
        inst.addTransformer(new ClassFileTransformer()
        {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException
            {
                if ("org/apache/maven/cli/MavenCli".equals(className))
                {
                    try
                    {
                        ClassPool classPool = new ClassPool(null);
                        classPool.appendClassPath(new LoaderClassPath(loader));

                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));

                        CtMethod loadCoreExtensionsMethod = ctClass.getDeclaredMethod("loadCoreExtensions");
                        transformLoadCoreExtensionsMethod(loadCoreExtensionsMethod);

                        classfileBuffer = ctClass.toBytecode();
                        ctClass.detach();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                return classfileBuffer;
            }
        });
    }

    private static void transformLoadCoreExtensionsMethod(CtMethod m)
    throws CannotCompileException
    {
        m.instrument(new ExprEditor()
        {
            @Override
            public void edit(MethodCall c) throws CannotCompileException
            {
                //Want to always get to readCoreExtensionsDescriptor() code in MavenCli.loadCoreExtensions()
                //so we ensure this code:

//                File extensionsFile = new File( cliRequest.multiModuleProjectDirectory, EXTENSIONS_FILENAME );
//                if ( !extensionsFile.isFile() )
//                {
//                    return Collections.emptyList();
//                }

                //always returns true by changing the isFile() call to omit the check if we are dealing with a file named ".../.mvn/extensions.xml"

                if (File.class.getName().equals(c.getClassName()) && "isFile".equals(c.getMethodName()))
                {
                    c.replace("if (\"extensions.xml\".equals($0.getName()) && \".mvn\".equals($0.getParentFile().getName())) { " +
                    "  $_ = true; " +
                    "} else { $_ = $proceed($$); }");
                }



                //readCoreExtensionsDescriptor() call needs to be updated
                // - if project-specific extensions.xml file exists, call readCoreExtensionsDescriptor() and save results to list
                // - if user-global extensions.xml in .m2 directory exists, cal readCoreExtensionsDescriptor() with this file and save results to list
                else if ("org.apache.maven.cli.MavenCli".equals(c.getClassName()) && "readCoreExtensionsDescriptor".equals(c.getMethodName()))
                {
                    c.replace(
                            //Initialize list
                            "$_ = new java.util.ArrayList(); " +

                            //Read project extensions.xml file first, if file exists
                            "if ($1.isFile()) { $_.addAll($proceed($$)); } " +


                            //Now try a user-global extensions.xml file, if it exists
                            "if (new java.io.File(USER_MAVEN_CONFIGURATION_HOME, \"extensions.xml\").isFile()) { $_.addAll($proceed(new java.io.File(USER_MAVEN_CONFIGURATION_HOME, \"extensions.xml\"))); } " +

                            //Dedupe the entries
                            "if (!$_.isEmpty()) { " + UserExtensionsJvmAgent.class.getName() + ".deduplicateExtensionEntries($_); } "
                    );
                }
            }
        });
    }

    /**
     * Deduplicate extensions that might have same groupId/artifactId but different versions.  First one in the list wins.
     * This is so that extensions added at project level can override the versions of user-global extensions with different versions if needed.
     * Project-level always wins against user-level.
     * <p>
     *
     * Cannot actually refer to these classes directly because we are an agent and don't have any Maven stuff in our classloader.
     * So everything is done through reflection.
     *
     * @param extensionEntries a list of {@link org.apache.maven.cli.internal.extension.model.CoreExtension} objects.
     */
    public static <T> void deduplicateExtensionEntries(List<T> extensionEntries)
    {
        Set<ExtensionEntry<T>> extensionEntriesSet = extensionEntries.stream()
                                                                     .map(ExtensionEntry::new)
                                                                     .collect(Collectors.toCollection(LinkedHashSet::new));
        extensionEntries.clear();
        extensionEntries.addAll(extensionEntriesSet.stream()
                                                   .map(ExtensionEntry::getCoreExtension)
                                                   .collect(Collectors.toList()));
    }

    /**
     * Wrap a {@link org.apache.maven.cli.internal.extension.model.CoreExtension} object and access it via reflection so we can handle it
     * with our classloader.
     */
    private static class ExtensionEntry<T>
    {
        private final T coreExtension;

        public ExtensionEntry(T coreExtension)
        {
            this.coreExtension = Objects.requireNonNull(coreExtension);
        }

        public String getGroupId()
        {
            return reflectionGet("getGroupId");
        }

        public String getArtifactId()
        {
            return reflectionGet("getArtifactId");
        }

        public T getCoreExtension()
        {
            return coreExtension;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ExtensionEntry<?> that = (ExtensionEntry<?>) o;
            return Objects.equals(getGroupId(), that.getGroupId())
                && Objects.equals(getArtifactId(), that.getArtifactId());
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(getGroupId(), getArtifactId());
        }

        @Override
        public String toString()
        {
            return reflectionGet("getId");
        }

        private String reflectionGet(String methodName)
        {
            try
            {
                Object result = coreExtension.getClass().getMethod(methodName).invoke(coreExtension);
                if (result == null)
                    return null;
                else
                    return result.toString();
            }
            catch (NoSuchMethodException e)
            {
                NoSuchMethodError err = new NoSuchMethodError(e.getMessage());
                err.initCause(e);
                throw err;
            }
            catch (IllegalAccessException e)
            {
                IllegalAccessError err = new IllegalAccessError(e.getMessage());
                err.initCause(e);
                throw err;
            }
            catch (InvocationTargetException e)
            {
                if (e.getCause() instanceof RuntimeException)
                    throw (RuntimeException)e.getCause();
                else if (e.getCause() instanceof Error)
                    throw (Error)e.getCause();
                else
                    throw new RuntimeException(e);
            }
        }
    }
}
