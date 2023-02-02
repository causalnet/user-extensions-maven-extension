package au.net.causal.maven.plugins.userextensions;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.security.ProtectionDomain;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class UserExtensionsJvmAgent
{
    public static void premain(String agentArgs, Instrumentation inst)
    {
        Set<Feature> features = Feature.parseArgs(agentArgs);

        inst.addTransformer(new ClassFileTransformer()
        {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException
            {
                try
                {
                    if ("org/apache/maven/cli/MavenCli".equals(className))
                    {
                        ClassPool classPool = new ClassPool(null);
                        classPool.appendClassPath(new LoaderClassPath(loader));

                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));

                        CtMethod loadCoreExtensionsMethod = ctClass.getDeclaredMethod("loadCoreExtensions");
                        transformLoadCoreExtensionsMethod(loadCoreExtensionsMethod);

                        classfileBuffer = ctClass.toBytecode();
                        ctClass.detach();
                    }
                    else if ("org/apache/maven/cli/internal/BootstrapCoreExtensionManager".equals(className) && features.contains(Feature.ENHANCED_INTERPOLATION))
                    {
                        ClassPool classPool = new ClassPool(null);
                        classPool.appendClassPath(new LoaderClassPath(loader));

                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));

                        CtMethod resolveCoreExtensionsMethod = ctClass.getDeclaredMethod("resolveCoreExtensions");
                        transformResolveCoreExtensionsMethod(resolveCoreExtensionsMethod);

                        CtMethod createInterpolatorMethod = ctClass.getDeclaredMethod("createInterpolator");
                        transformCreateInterpolatorMethod(createInterpolatorMethod);

                        classfileBuffer = ctClass.toBytecode();
                        ctClass.detach();
                    }
                }
                catch (Throwable e)
                {
                    e.printStackTrace();
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
                            //"System.err.println(\"userProperties: \" + cliRequest.userProperties);" +
                            //TODO support reading the user extensions.xml location from properties
                            "if (new java.io.File(USER_MAVEN_CONFIGURATION_HOME, \"extensions.xml\").isFile()) { $_.addAll($proceed(new java.io.File(USER_MAVEN_CONFIGURATION_HOME, \"extensions.xml\"))); } " +

                            //Dedupe the entries
                            "if (!$_.isEmpty()) { " + UserExtensionsJvmAgent.class.getName() + ".deduplicateExtensionEntries($_); } "
                    );
                }
            }
        });
    }

    private static void transformResolveCoreExtensionsMethod(CtMethod m)
    throws CannotCompileException
    {
        m.instrument(new ExprEditor()
        {
            @Override
            public void edit(MethodCall c) throws CannotCompileException
            {
                //If the version of the plugin after interpolation is determined to be 'disabled' then disregard that extension
                if ("org.apache.maven.cli.internal.BootstrapCoreExtensionManager".equals(c.getClassName()) && "resolveExtension".equals(c.getMethodName()))
                {
                    c.replace(
                        "if (\"disabled\".equalsIgnoreCase($5.interpolate($1.getVersion()))) { $_ = java.util.Collections.emptyList(); }" +
                        "else { $_ = $proceed($$); }"
                    );
                }
            }
        });
    }

    private static void transformCreateInterpolatorMethod(CtMethod m)
    throws CannotCompileException
    {
        try
        {
            m.addLocalVariable("i", m.getDeclaringClass().getClassPool().get("java.util.ListIterator"));
            m.addLocalVariable("p", m.getDeclaringClass().getClassPool().get("org.apache.maven.model.Profile"));
            m.addLocalVariable("p2", m.getDeclaringClass().getClassPool().get("java.util.Properties"));
            m.insertAfter(
                    "org.apache.maven.model.building.DefaultModelBuilder builder = new org.apache.maven.model.building.DefaultModelBuilderFactory().newInstance();" +

                    //Don't care that this model building fails (using a directory as POM file) but we want to grab the active profiles from the exception at the end
                    //and that gets resolved whether the POM file is real or not
                    "org.apache.maven.model.building.DefaultModelBuildingRequest request = new org.apache.maven.model.building.DefaultModelBuildingRequest().setPomFile(new java.io.File(\".\"));" +
                    "request.setProfiles($1.getProfiles());" +
                    "request.setActiveProfileIds($1.getActiveProfiles());" +
                    "request.setInactiveProfileIds($1.getInactiveProfiles());" +
                    "request.setSystemProperties($1.getSystemProperties());" +
                    "request.setUserProperties($1.getUserProperties());" +
                    "org.apache.maven.model.building.ModelBuildingResult result = null;" +
                    "try {" +
                    "  result = builder.build(request);" +
                    "} catch (org.apache.maven.model.building.ModelBuildingException ex) {" +
                    "  result = ex.getResult();" +
                    "} " +
                    "java.util.List activeProfiles = result.getActiveExternalProfiles();" +

                    //Now we have resolved active profiles, add value sources in reverse order
                    //This is the way Maven works as well normally for properties in multiple profiles (later profiles should win), tested with Antrun
                    "for (i = activeProfiles.listIterator(activeProfiles.size()); i.hasPrevious();) { " +
                    "   p = i.previous();" +
                    "   p2 = org.apache.maven.model.Profile.class.getMethod(\"getProperties\", null).invoke(p, null);" +
                    "   $_.addValueSource(new org.codehaus.plexus.interpolation.MapBasedValueSource(p2));" +
                    "}"
            );
        }
        catch (NotFoundException e)
        {
            throw new CannotCompileException(e);
        }
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
    //TODO don't use this we want to avoid having to make extra class available
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

    public static enum Feature
    {
        ENHANCED_INTERPOLATION;

        public static Set<Feature> parseArgs(String args)
        {
            EnumSet<Feature> features = EnumSet.noneOf(Feature.class);

            if (args != null)
            {
                for (String arg : args.split(Pattern.quote("\\s+,\\s+")))
                {
                    try
                    {
                        features.add(Feature.valueOf(arg.toUpperCase(Locale.ROOT)));
                    }
                    catch (IllegalArgumentException e)
                    {
                        System.err.println("Warning: unknown user-extensions agent feature: " + arg);
                    }
                }
            }

            return features;
        }
    }
}
